package com.modu.backend.domain.market.service;

import com.modu.backend.domain.market.client.KisCandleClient;
import com.modu.backend.domain.market.dto.CandleResponse;
import com.modu.backend.domain.market.repository.StockMinuteCandleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 분봉 캔들 캐시 + KIS 보충 서비스 — S14P31B106-365
 *
 * 흐름:
 *  1. 요청 [startDate, endDate] 일자 중 loaded_date 마커에 없는 일자 = missing
 *     (today 는 Phase 5 정책상 항상 KIS 직접 호출 / DB 적재 제외)
 *  2. missing 있거나 today 포함되면 KIS 213 페이징 호출
 *  3. KIS 응답을 일자별 그룹화 — today 외 일자만 INSERT + markLoaded
 *  4. 응답 = DB SELECT (오늘 외 일자) + today KIS 결과(있다면) 머지 후 period aggregate
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MinuteCandleService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final Duration LOCK_TTL = Duration.ofSeconds(10);
    private static final long LOCK_POLL_INTERVAL_MS = 200L;
    private static final int LOCK_POLL_RETRY = 3;

    private final KisCandleClient kisCandleClient;
    private final StockMinuteCandleRepository repository;
    private final KisPlatformTokenService kisPlatformTokenService;
    private final KisMinuteCandleLockService lockService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<CandleResponse> getMinuteCandles(String stockCode, String period,
                                                  String startDate, String endDate) {
        String resolvedEnd = (endDate != null) ? endDate : LocalDate.now(KST).format(DATE_FMT);
        String resolvedStart = (startDate != null) ? startDate : resolvedEnd;
        LocalDate startD = LocalDate.parse(resolvedStart, DATE_FMT);
        LocalDate endD = LocalDate.parse(resolvedEnd, DATE_FMT);
        LocalDate today = LocalDate.now(KST);

        MissingInfo missingInfo = computeMissing(stockCode, startD, endD, today);
        List<CandleResponse> todayFromKis = List.of();

        if (missingInfo.needsKis()) {
            boolean acquired = lockService.tryLock(stockCode, LOCK_TTL);
            if (!acquired) {
                // 다른 pod 이 KIS 호출 중 — 짧게 polling 으로 적재 완료 대기
                missingInfo = waitForOtherPodAndRecompute(stockCode, startD, endD, today);
            }
            try {
                if (missingInfo.needsKis()) {
                    todayFromKis = fetchAndStoreFromKis(stockCode, startD, endD, today, missingInfo);
                }
            } finally {
                if (acquired) lockService.unlock(stockCode);
            }
        }

        LocalDateTime fromTs = LocalDateTime.of(startD, LocalTime.MIN);
        LocalDateTime toTs = LocalDateTime.of(endD, LocalTime.of(23, 59, 59));
        List<CandleResponse> dbCandles = repository.findInRange(stockCode, fromTs, toTs);

        List<CandleResponse> merged = mergeUnique(dbCandles, todayFromKis);
        merged.sort(Comparator.comparing(CandleResponse::timestamp));

        return kisCandleClient.aggregate(merged, period);
    }

    private MissingInfo computeMissing(String stockCode, LocalDate startD, LocalDate endD, LocalDate today) {
        Set<LocalDate> loaded = repository.findLoadedDates(stockCode, startD, endD);
        List<LocalDate> missing = new ArrayList<>();
        boolean todayRequested = false;
        for (LocalDate d = startD; !d.isAfter(endD); d = d.plusDays(1)) {
            if (d.equals(today)) {
                todayRequested = true;
                continue;
            }
            if (!loaded.contains(d)) missing.add(d);
        }
        return new MissingInfo(missing, todayRequested);
    }

    private MissingInfo waitForOtherPodAndRecompute(String stockCode, LocalDate startD, LocalDate endD, LocalDate today) {
        for (int i = 0; i < LOCK_POLL_RETRY; i++) {
            try {
                Thread.sleep(LOCK_POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            MissingInfo recomputed = computeMissing(stockCode, startD, endD, today);
            if (!recomputed.needsKis()) {
                log.debug("Minute candle 락 polling 으로 적재 완료 감지 - stockCode: {}", stockCode);
                return recomputed;
            }
        }
        // 락 hang / 다른 pod 실패 케이스 대비 fallback — 락 없이 직접 KIS 호출
        log.warn("Minute candle 락 polling 후에도 missing 잔존 — fallback 직접 호출, stockCode: {}", stockCode);
        return computeMissing(stockCode, startD, endD, today);
    }

    private List<CandleResponse> fetchAndStoreFromKis(String stockCode, LocalDate startD, LocalDate endD,
                                                      LocalDate today, MissingInfo info) {
        LocalDate kisStart = info.missing().isEmpty() ? today : info.missing().get(0);
        String accessToken = kisPlatformTokenService.getAccessToken();
        KisCandleClient.MinuteCandlePage page = kisCandleClient.fetchRawMinuteCandles(
                accessToken, stockCode, kisStart.format(DATE_FMT), endD.format(DATE_FMT));

        Map<LocalDate, List<CandleResponse>> grouped = groupByDate(page.candles());

        LocalDate partialDate = (!page.completed() && !grouped.isEmpty())
                ? grouped.keySet().stream().min(LocalDate::compareTo).orElse(null)
                : null;

        for (Map.Entry<LocalDate, List<CandleResponse>> entry : grouped.entrySet()) {
            LocalDate date = entry.getKey();
            if (date.equals(today)) continue;
            if (date.isBefore(startD) || date.isAfter(endD)) continue;
            List<CandleResponse> dayCandles = entry.getValue();
            repository.saveCandles(stockCode, dayCandles);
            if (!date.equals(partialDate)) {
                repository.markLoaded(stockCode, date, dayCandles.size());
            }
        }

        if (page.completed()) {
            for (LocalDate d : info.missing()) {
                if (!grouped.containsKey(d) && !d.isAfter(today)) {
                    repository.markLoaded(stockCode, d, 0);
                }
            }
        }

        return (info.todayRequested() && grouped.containsKey(today)) ? grouped.get(today) : List.of();
    }

    private record MissingInfo(List<LocalDate> missing, boolean todayRequested) {
        boolean needsKis() {
            return !missing.isEmpty() || todayRequested;
        }
    }

    private Map<LocalDate, List<CandleResponse>> groupByDate(List<CandleResponse> candles) {
        Map<LocalDate, List<CandleResponse>> grouped = new LinkedHashMap<>();
        for (CandleResponse c : candles) {
            String ts = c.timestamp();
            if (ts == null || ts.length() < 14) continue;
            LocalDate date = LocalDate.parse(ts.substring(0, 8), DATE_FMT);
            grouped.computeIfAbsent(date, k -> new ArrayList<>()).add(c);
        }
        return grouped;
    }

    private List<CandleResponse> mergeUnique(List<CandleResponse> a, List<CandleResponse> b) {
        Set<String> seen = new HashSet<>();
        List<CandleResponse> result = new ArrayList<>(a.size() + b.size());
        for (CandleResponse c : a) {
            if (seen.add(c.timestamp())) result.add(c);
        }
        for (CandleResponse c : b) {
            if (seen.add(c.timestamp())) result.add(c);
        }
        return result;
    }
}
