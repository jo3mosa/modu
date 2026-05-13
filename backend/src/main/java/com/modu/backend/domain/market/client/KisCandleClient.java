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
    private static final String INTRADAY_CHART_PATH = "/uapi/domestic-stock/v1/quotations/inquire-time-dailychartprice";
    private static final String MINUTE_CHART_PATH = "/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice";

    private static final String TR_DAILY = "FHKST03010100";
    private static final String TR_INTRADAY = "FHKST03010200";
    private static final String TR_MINUTE = "FHKST03010230";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HHmmss");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final RestClient kisRestClient;
    private final KisApiProperties kisApiProperties;

    public KisCandleClient(RestClient kisRestClient, KisApiProperties kisApiProperties) {
        this.kisRestClient = kisRestClient;
        this.kisApiProperties = kisApiProperties;
    }

    public List<CandleResponse> getCandles(String accessToken, String stockCode,
                                           String period, String startDate, String endDate) {
        String resolvedEnd = (endDate != null) ? endDate : LocalDate.now(KST).format(DATE_FMT);

        return switch (period) {
            case "D", "W", "M" -> getDailyCandles(accessToken, stockCode, period, startDate, resolvedEnd);
            case "1", "5", "60" -> getMinuteCandles(accessToken, stockCode, period, startDate, resolvedEnd);
            default -> throw new ApiException(MarketErrorCode.INVALID_CANDLE_PERIOD);
        };
    }

    private List<CandleResponse> getDailyCandles(String accessToken, String stockCode,
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

    private List<CandleResponse> getMinuteCandles(String accessToken, String stockCode,
                                                  String period, String startDate, String endDate) {
        String today = LocalDate.now(KST).format(DATE_FMT);
        String resolvedStart = startDate != null ? startDate : endDate;

        if (resolvedStart.equals(today) && endDate.equals(today)) {
            return getTodayMinuteCandles(accessToken, stockCode, period, endDate);
        }
        return getHistoricalMinuteCandles(accessToken, stockCode, period, resolvedStart, endDate);
    }

    private List<CandleResponse> getTodayMinuteCandles(String accessToken, String stockCode, String period, String date) {
        String currentTime = LocalTime.now(KST).format(TIME_FMT);

        try {
            MinuteChartResponse response = kisRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(INTRADAY_CHART_PATH)
                            .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                            .queryParam("FID_INPUT_ISCD", stockCode)
                            .queryParam("FID_INPUT_DATE_1", date)
                            .queryParam("FID_INPUT_OUTC_TIME", currentTime)
                            .queryParam("FID_PW_DATA_INCU_YN", "Y")
                            .build())
                    .header("content-type", "application/json")
                    .header("authorization", "Bearer " + accessToken)
                    .header("appkey", kisApiProperties.getAppKey())
                    .header("appsecret", kisApiProperties.getAppSecret())
                    .header("tr_id", TR_INTRADAY)
                    .header("custtype", "P")
                    .retrieve()
                    .body(MinuteChartResponse.class);

            if (response == null || !"0".equals(response.rtCd())) {
                log.error("KIS today minute candle fetch failed - stockCode: {}, msg: {}",
                        stockCode, response != null ? response.msg1() : "null");
                throw new ApiException(MarketErrorCode.KIS_PRICE_FETCH_FAILED);
            }

            if (response.output2() == null) {
                return Collections.emptyList();
            }

            List<CandleResponse> candles = response.output2().stream()
                    .filter(item -> item.stckCntgHour() != null && !item.stckCntgHour().isBlank())
                    .map(item -> new CandleResponse(
                            item.stckCntgHour(),
                            parseLong(item.stckOprc()),
                            parseLong(item.stckHgpr()),
                            parseLong(item.stckLwpr()),
                            parseLong(item.stckPrpr()),
                            parseLong(item.cntgVol())
                    ))
                    .toList();

            return aggregateMinuteCandles(candles.reversed(), period);
        } catch (RestClientException e) {
            log.error("KIS today minute candle API call failed - stockCode: {}, error: {}", stockCode, e.getMessage());
            throw new ApiException(MarketErrorCode.KIS_PRICE_FETCH_FAILED);
        }
    }

    private List<CandleResponse> getHistoricalMinuteCandles(String accessToken, String stockCode,
                                                            String period, String startDate, String endDate) {
        LocalDate start = LocalDate.parse(startDate, DATE_FMT);
        LocalDate end = LocalDate.parse(endDate, DATE_FMT);
        if (end.isBefore(start)) {
            throw new ApiException(MarketErrorCode.INVALID_CANDLE_DATE_RANGE);
        }

        List<CandleResponse> candles = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            candles.addAll(getHistoricalMinuteCandlesByDate(accessToken, stockCode, date.format(DATE_FMT)));
        }
        return aggregateMinuteCandles(candles, period);
    }

    private List<CandleResponse> getHistoricalMinuteCandlesByDate(String accessToken, String stockCode, String date) {
        try {
            MinuteChartResponse response = kisRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(MINUTE_CHART_PATH)
                            .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                            .queryParam("FID_INPUT_ISCD", stockCode)
                            .queryParam("FID_INPUT_HOUR_1", "153000")
                            .queryParam("FID_INPUT_DATE_1", date)
                            .queryParam("FID_PW_DATA_INCU_YN", "Y")
                            .queryParam("FID_FAKE_TICK_INCU_YN", "")
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
                log.error("KIS historical minute candle fetch failed - stockCode: {}, date: {}, msg: {}",
                        stockCode, date, response != null ? response.msg1() : "null");
                throw new ApiException(MarketErrorCode.KIS_PRICE_FETCH_FAILED);
            }

            if (response.output2() == null) {
                return Collections.emptyList();
            }

            List<CandleResponse> candles = response.output2().stream()
                    .filter(item -> item.stckCntgHour() != null && !item.stckCntgHour().isBlank())
                    .map(item -> new CandleResponse(
                            date + item.stckCntgHour(),
                            parseLong(item.stckOprc()),
                            parseLong(item.stckHgpr()),
                            parseLong(item.stckLwpr()),
                            parseLong(item.stckPrpr()),
                            parseLong(item.cntgVol())
                    ))
                    .toList();

            return candles.reversed();
        } catch (RestClientException e) {
            log.error("KIS historical minute candle API call failed - stockCode: {}, date: {}, error: {}",
                    stockCode, date, e.getMessage());
            throw new ApiException(MarketErrorCode.KIS_PRICE_FETCH_FAILED);
        }
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
            @JsonProperty("stck_cntg_hour") String stckCntgHour,
            @JsonProperty("stck_oprc") String stckOprc,
            @JsonProperty("stck_hgpr") String stckHgpr,
            @JsonProperty("stck_lwpr") String stckLwpr,
            @JsonProperty("stck_prpr") String stckPrpr,
            @JsonProperty("cntg_vol") String cntgVol
    ) {}
}
