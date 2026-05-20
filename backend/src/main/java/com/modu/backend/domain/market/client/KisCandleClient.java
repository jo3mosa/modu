package com.modu.backend.domain.market.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.modu.backend.domain.market.dto.CandleResponse;
import com.modu.backend.domain.market.exception.MarketErrorCode;
import com.modu.backend.global.config.KisApiProperties;
import com.modu.backend.global.error.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class KisCandleClient {

    private static final String DAILY_CHART_PATH = "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice";
    // 주식일별분봉조회 (국내주식-213) — 당일/과거 분봉 통합 경로
    private static final String MINUTE_CHART_PATH = "/uapi/domestic-stock/v1/quotations/inquire-time-dailychartprice";

    private static final String TR_DAILY = "FHKST03010100";
    private static final String TR_MINUTE = "FHKST03010230";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HHmmss");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 213 페이징 안전 한도 — 1회 120건 × 50 = 6000건 (1분봉 약 15거래일치)
    private static final int MAX_PAGE_ITER = 50;
    // 장 마감 시각 — endDate 기준 페이징 시작 커서
    private static final String DEFAULT_END_TIME = "153000";
    // KIS 실전 rate limit (초당 20건) 대비 페이징 호출 간격 — 실전 안전 마진 포함
    private static final long PAGING_SLEEP_MS = 50L;

    private final RestClient kisRestClient;
    private final KisApiProperties kisApiProperties;

    public KisCandleClient(RestClient kisRestClient, KisApiProperties kisApiProperties) {
        this.kisRestClient = kisRestClient;
        this.kisApiProperties = kisApiProperties;
    }

    // 일봉/주봉/월봉만 처리 — 분봉은 MinuteCandleService 가 캐시 + KIS 보충 후 호출
    public List<CandleResponse> getDailyCandles(String accessToken, String stockCode,
                                                String period, String startDate, String endDate) {
        String resolvedEnd = (endDate != null) ? endDate : LocalDate.now(KST).format(DATE_FMT);
        return switch (period) {
            case "D", "W", "M" -> getDailyCandlesInternal(accessToken, stockCode, period, startDate, resolvedEnd);
            default -> throw new ApiException(MarketErrorCode.INVALID_CANDLE_PERIOD);
        };
    }

    private List<CandleResponse> getDailyCandlesInternal(String accessToken, String stockCode,
                                                          String period, String startDate, String endDate) {
        String resolvedStart = (startDate != null) ? startDate : defaultStartDate(period);

        try {
            DailyChartResponse response = kisRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(DAILY_CHART_PATH)
                            .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                            .queryParam("FID_INPUT_ISCD", stockCode)
                            .queryParam("FID_INPUT_DATE_1", resolvedStart)
                            .queryParam("FID_INPUT_DATE_2", endDate)
                            .queryParam("FID_PERIOD_DIV_CODE", period)
                            .queryParam("FID_ORG_ADJ_PRC", "0")
                            .build())
                    .header("content-type", "application/json")
                    .header("authorization", "Bearer " + accessToken)
                    .header("appkey", kisApiProperties.getAppKey())
                    .header("appsecret", kisApiProperties.getAppSecret())
                    .header("tr_id", TR_DAILY)
                    .header("custtype", "P")
                    .retrieve()
                    .body(DailyChartResponse.class);

            if (response == null || !"0".equals(response.rtCd())) {
                log.error("KIS daily candle fetch failed - stockCode: {}, rtCd: {}, msg: {}",
                        stockCode,
                        response != null ? response.rtCd() : "null",
                        response != null ? response.msg1() : "null");
                throw new ApiException(MarketErrorCode.KIS_PRICE_FETCH_FAILED);
            }

            if (response.output2() == null) {
                return Collections.emptyList();
            }

            List<CandleResponse> candles = response.output2().stream()
                    .filter(item -> item.stckBsopDate() != null && !item.stckBsopDate().isBlank())
                    .map(item -> new CandleResponse(
                            item.stckBsopDate(),
                            parseLong(item.stckOprc()),
                            parseLong(item.stckHgpr()),
                            parseLong(item.stckLwpr()),
                            parseLong(item.stckClpr()),
                            parseLong(item.acmlVol())
                    ))
                    .toList();

            return candles.reversed();
        } catch (RestClientException e) {
            log.error("KIS daily candle API call failed - stockCode: {}, error: {}", stockCode, e.getMessage());
            throw new ApiException(MarketErrorCode.KIS_PRICE_FETCH_FAILED);
        }
    }

    // KIS 213 raw 페이징 결과 노출 — 캐시 레이어가 일자 단위 적재용으로 사용
    public MinuteCandlePage fetchRawMinuteCandles(String accessToken, String stockCode,
                                                   String startDate, String endDate) {
        return fetchMinuteCandlesWithPaging(accessToken, stockCode, startDate, endDate);
    }

    // 페이징 결과 — completed=false 면 rate limit/오류로 중간에 끊긴 부분 응답
    public record MinuteCandlePage(List<CandleResponse> candles, boolean completed) {}

    // 분봉 aggregate(5/60분) 진입점 — 캐시 레이어에서 호출
    public List<CandleResponse> aggregate(List<CandleResponse> candles, String period) {
        return aggregateMinuteCandles(candles, period);
    }

    // 213 페이징 — endDate+153000부터 시작, 응답 마지막(가장 오래된) 캔들 -1분으로 커서 이동
    // rate limit 등으로 도중 실패 시 받은 데이터까지 + completed=false 로 반환
    private MinuteCandlePage fetchMinuteCandlesWithPaging(String accessToken, String stockCode,
                                                          String startDate, String endDate) {
        List<CandleResponse> accumulated = new ArrayList<>();
        String cursorDate = endDate;
        String cursorTime = DEFAULT_END_TIME;

        for (int i = 0; i < MAX_PAGE_ITER; i++) {
            if (i > 0) {
                try {
                    Thread.sleep(PAGING_SLEEP_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return new MinuteCandlePage(accumulated, false);
                }
            }

            MinuteChartResponse response;
            try {
                response = callMinuteApi(accessToken, stockCode, cursorDate, cursorTime);
            } catch (ApiException ex) {
                log.warn("KIS minute candle paging interrupted - stockCode: {}, page: {}, accumulated: {} candles",
                        stockCode, i, accumulated.size());
                return new MinuteCandlePage(accumulated, false);
            }

            if (response.output2() == null || response.output2().isEmpty()) break;

            List<CandleResponse> page = response.output2().stream()
                    .filter(item -> item.stckBsopDate() != null && !item.stckBsopDate().isBlank())
                    .filter(item -> item.stckCntgHour() != null && !item.stckCntgHour().isBlank())
                    .map(item -> new CandleResponse(
                            item.stckBsopDate() + item.stckCntgHour(),
                            parseLong(item.stckOprc()),
                            parseLong(item.stckHgpr()),
                            parseLong(item.stckLwpr()),
                            parseLong(item.stckPrpr()),
                            parseLong(item.cntgVol())
                    ))
                    .toList();

            if (page.isEmpty()) break;
            accumulated.addAll(page);

            // 가장 오래된 캔들 = 응답 마지막
            CandleResponse oldest = page.get(page.size() - 1);
            String oldestTs = oldest.timestamp();
            String oldestDate = oldestTs.substring(0, 8);

            if (oldestDate.compareTo(startDate) < 0) break;

            // 다음 커서: 오래된 캔들 -1분 (동일 시각 중복 방지)
            String[] next = decrementOneMinute(oldestDate, oldestTs.substring(8, 14));
            cursorDate = next[0];
            cursorTime = next[1];
        }

        return new MinuteCandlePage(accumulated, true);
    }

    private MinuteChartResponse callMinuteApi(String accessToken, String stockCode,
                                              String date, String time) {
        try {
            MinuteChartResponse response = kisRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(MINUTE_CHART_PATH)
                            .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                            .queryParam("FID_INPUT_ISCD", stockCode)
                            .queryParam("FID_INPUT_HOUR_1", time)
                            .queryParam("FID_INPUT_DATE_1", date)
                            .queryParam("FID_PW_DATA_INCU_YN", "Y")
                            .queryParam("FID_FAKE_TICK_INCU_YN", "N")
                            .build())
                    .header("content-type", "application/json")
                    .header("authorization", "Bearer " + accessToken)
                    .header("appkey", kisApiProperties.getAppKey())
                    .header("appsecret", kisApiProperties.getAppSecret())
                    .header("tr_id", TR_MINUTE)
                    .header("custtype", "P")
                    .retrieve()
                    .body(MinuteChartResponse.class);

            if (response == null || !"0".equals(response.rtCd())) {
                log.error("KIS minute candle fetch failed - stockCode: {}, date: {}, time: {}, rtCd: {}, msg: {}",
                        stockCode, date, time,
                        response != null ? response.rtCd() : "null",
                        response != null ? response.msg1() : "null");
                throw new ApiException(MarketErrorCode.KIS_PRICE_FETCH_FAILED);
            }
            return response;
        } catch (RestClientException e) {
            log.error("KIS minute candle API call failed - stockCode: {}, date: {}, time: {}, error: {}",
                    stockCode, date, time, e.getMessage());
            throw new ApiException(MarketErrorCode.KIS_PRICE_FETCH_FAILED);
        }
    }

    private String[] decrementOneMinute(String date, String time) {
        LocalDate d = LocalDate.parse(date, DATE_FMT);
        LocalTime t = LocalTime.parse(time, TIME_FMT);
        LocalDateTime prev = LocalDateTime.of(d, t).minusMinutes(1);
        return new String[]{
                prev.toLocalDate().format(DATE_FMT),
                prev.toLocalTime().format(TIME_FMT)
        };
    }

    private List<CandleResponse> aggregateMinuteCandles(List<CandleResponse> candles, String period) {
        int intervalMinutes = Integer.parseInt(period);
        if (intervalMinutes <= 1 || candles.isEmpty()) {
            return candles;
        }

        Map<String, List<CandleResponse>> grouped = new LinkedHashMap<>();
        for (CandleResponse candle : candles) {
            grouped.computeIfAbsent(bucketTimestamp(candle.timestamp(), intervalMinutes), ignored -> new ArrayList<>())
                    .add(candle);
        }

        return grouped.entrySet().stream()
                .map(entry -> aggregateBucket(entry.getKey(), entry.getValue()))
                .toList();
    }

    private String bucketTimestamp(String timestamp, int intervalMinutes) {
        int timeStartIndex = timestamp.length() - 6;
        String datePrefix = timestamp.substring(0, timeStartIndex);
        String time = timestamp.substring(timeStartIndex);

        int hour = Integer.parseInt(time.substring(0, 2));
        int minute = Integer.parseInt(time.substring(2, 4));
        int bucketMinute = (minute / intervalMinutes) * intervalMinutes;

        return datePrefix + String.format("%02d%02d00", hour, bucketMinute);
    }

    private CandleResponse aggregateBucket(String timestamp, List<CandleResponse> bucket) {
        CandleResponse first = bucket.get(0);
        CandleResponse last = bucket.get(bucket.size() - 1);

        long highPrice = bucket.stream()
                .mapToLong(candle -> valueOrZero(candle.highPrice()))
                .max()
                .orElse(0L);
        long lowPrice = bucket.stream()
                .map(CandleResponse::lowPrice)
                .filter(value -> value != null)
                .mapToLong(Long::longValue)
                .min()
                .orElse(0L);
        long volume = bucket.stream()
                .mapToLong(candle -> valueOrZero(candle.volume()))
                .sum();

        return new CandleResponse(
                timestamp,
                first.openPrice(),
                highPrice,
                lowPrice,
                last.closePrice(),
                volume
        );
    }

    private String defaultStartDate(String period) {
        return switch (period) {
            case "D" -> LocalDate.now(KST).minusMonths(6).format(DATE_FMT);
            case "W" -> LocalDate.now(KST).minusYears(2).format(DATE_FMT);
            case "M" -> LocalDate.now(KST).minusYears(5).format(DATE_FMT);
            default -> LocalDate.now(KST).format(DATE_FMT);
        };
    }

    private long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.error("KIS candle number parse failed - value: '{}'", value);
            return 0L;
        }
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private record DailyChartResponse(
            @JsonProperty("rt_cd") String rtCd,
            @JsonProperty("msg_cd") String msgCd,
            @JsonProperty("msg1") String msg1,
            @JsonProperty("output2") List<DailyOutput> output2
    ) {}

    private record DailyOutput(
            @JsonProperty("stck_bsop_date") String stckBsopDate,
            @JsonProperty("stck_oprc") String stckOprc,
            @JsonProperty("stck_hgpr") String stckHgpr,
            @JsonProperty("stck_lwpr") String stckLwpr,
            @JsonProperty("stck_clpr") String stckClpr,
            @JsonProperty("acml_vol") String acmlVol
    ) {}

    private record MinuteChartResponse(
            @JsonProperty("rt_cd") String rtCd,
            @JsonProperty("msg_cd") String msgCd,
            @JsonProperty("msg1") String msg1,
            @JsonProperty("output2") List<MinuteOutput> output2
    ) {}

    private record MinuteOutput(
            @JsonProperty("stck_bsop_date") String stckBsopDate,
            @JsonProperty("stck_cntg_hour") String stckCntgHour,
            @JsonProperty("stck_oprc") String stckOprc,
            @JsonProperty("stck_hgpr") String stckHgpr,
            @JsonProperty("stck_lwpr") String stckLwpr,
            @JsonProperty("stck_prpr") String stckPrpr,
            @JsonProperty("cntg_vol") String cntgVol
    ) {}
}
