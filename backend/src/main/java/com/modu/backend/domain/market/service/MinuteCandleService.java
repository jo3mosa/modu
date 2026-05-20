package com.modu.backend.domain.market.service;

import com.modu.backend.domain.market.client.KisCandleClient;
import com.modu.backend.domain.market.dto.CandleResponse;
import com.modu.backend.domain.market.repository.StockMinuteCandleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    private final KisCandleClient kisCandleClient;
    private final StockMinuteCandleRepository repository;
    private final KisPlatformTokenService kisPlatformTokenService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<CandleResponse> getMinuteCandles(String stockCode, String period,
                                                  String startDate, String endDate) {
        String resolvedEnd = (endDate != null) ? endDate : LocalDate.now(KST).format(DATE_FMT);
        String resolvedStart = (startDate != null) ? startDate : resolvedEnd;
        LocalDate startD = LocalDate.parse(resolvedStart, DATE_FMT);
        LocalDate endD = LocalDate.parse(resolvedEnd, DATE_FMT);
        LocalDate today = LocalDate.now(KST);

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

        List<CandleResponse> todayFromKis = List.of();
        if (!missing.isEmpty() || todayRequested) {
            LocalDate kisStart = missing.isEmpty() ? today : missing.get(0);
            String accessToken = kisPlatformTokenService.getAccessToken();
            KisCandleClient.MinuteCandlePage page = kisCandleClient.fetchRawMinuteCandles(
                    accessToken, stockCode, kisStart.format(DATE_FMT), endD.format(DATE_FMT));

            Map<LocalDate, List<CandleResponse>> grouped = groupByDate(page.candles());

            // 부분 응답이면 가장 오래된 일자(페이징이 끊긴 지점)는 마커 박지 않음 — 다음 호출에서 재시도 보장
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

            // 페이징 정상 종료 시: missing 중 응답에 없던 일자는 휴장일로 간주, 마커만 박음 (candle_count=0)
            if (page.completed()) {
                for (LocalDate d : missing) {
                    if (!grouped.containsKey(d) && !d.isAfter(today)) {
                        repository.markLoaded(stockCode, d, 0);
                    }
                }
            }

            if (todayRequested && grouped.containsKey(today)) {
                todayFromKis = grouped.get(today);
            }
        }

        LocalDateTime fromTs = LocalDateTime.of(startD, LocalTime.MIN);
        LocalDateTime toTs = LocalDateTime.of(endD, LocalTime.of(23, 59, 59));
        List<CandleResponse> dbCandles = repository.findInRange(stockCode, fromTs, toTs);

        List<CandleResponse> merged = mergeUnique(dbCandles, todayFromKis);
        merged.sort(Comparator.comparing(CandleResponse::timestamp));

        return kisCandleClient.aggregate(merged, period);
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
