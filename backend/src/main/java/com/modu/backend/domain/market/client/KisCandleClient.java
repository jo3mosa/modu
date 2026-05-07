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
import java.util.Objects;

/**
 * KIS 캔들 데이터 조회 클라이언트
 *
 * [Period → KIS API 매핑]
 * - D/W/M : inquire-daily-itemchartprice (FHKST03010100) 기간별시세
 * - 1/5/60 (startDate 없음) : inquire-time-dailychartprice (FHKST03010200) 당일분봉
 * - 1/5/60 (startDate 있음) : 주식일별분봉조회 (FHKST03010230)
 *
 * [startDate 기본값]
 * - D  : 오늘 - 6개월
 * - W  : 오늘 - 2년
 * - M  : 오늘 - 5년
 * - 분봉: 오늘 (당일 API 사용)
 */
@Slf4j
@Component
public class KisCandleClient {

    private static final String DAILY_CHART_PATH = "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice";
    private static final String INTRADAY_CHART_PATH = "/uapi/domestic-stock/v1/quotations/inquire-time-dailychartprice";
    private static final String MINUTE_CHART_PATH = "/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice";

    private static final String TR_DAILY = "FHKST03010100";
    private static final String TR_INTRADAY = "FHKST03010200";
    private static final String TR_MINUTE = "FHKST03010230";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 국내 주식 장 기준 타임존 — UTC 배포 환경에서도 날짜/시간 판정을 KST 기준으로 고정 */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final RestClient kisRestClient;
    private final KisApiProperties kisApiProperties;

    public KisCandleClient(RestClient kisRestClient, KisApiProperties kisApiProperties) {
        this.kisRestClient = kisRestClient;
        this.kisApiProperties = kisApiProperties;
    }

    /**
     * 캔들 데이터 조회 (period에 따라 KIS API 자동 선택)
     *
     * @param accessToken 플랫폼 KIS 액세스 토큰
     * @param stockCode   종목코드
     * @param period      D/W/M/1/5/60
     * @param startDate   시작일 (YYYYMMDD, null이면 period별 기본값)
     * @param endDate     종료일 (YYYYMMDD, null이면 오늘)
     */
    public List<CandleResponse> getCandles(String accessToken, String stockCode,
                                            String period, String startDate, String endDate) {
        String resolvedEnd = (endDate != null) ? endDate : LocalDate.now(KST).format(DATE_FMT);

        return switch (period) {
            case "D", "W", "M" -> getDailyCandles(accessToken, stockCode, period, startDate, resolvedEnd);
            case "1", "5", "60" -> getMinuteCandles(accessToken, stockCode, period, startDate, resolvedEnd);
            default -> throw new ApiException(MarketErrorCode.INVALID_CANDLE_PERIOD);
        };
    }

    // ── 일/주/월봉 ──────────────────────────────────────────────────────────────

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
                    .header("content-type", "application/json; charset=utf-8")
                    .header("authorization", "Bearer " + accessToken)
                    .header("appkey", kisApiProperties.getAppKey())
                    .header("appsecret", kisApiProperties.getAppSecret())
                    .header("tr_id", TR_DAILY)
                    .header("custtype", "P")
                    .retrieve()
                    .body(DailyChartResponse.class);

            if (response == null || !"0".equals(response.rtCd())) {
                log.error("KIS 기간별시세 조회 실패 - stockCode: {}, rtCd: {}, msg: {}",
                        stockCode, response != null ? response.rtCd() : "null",
                        response != null ? response.msg1() : "null");
                throw new ApiException(MarketErrorCode.KIS_PRICE_FETCH_FAILED);
            }

            if (response.output2() == null) return Collections.emptyList();

            // KIS 응답은 최신순 → 과거순으로 역정렬
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
            log.error("KIS 기간별시세 API 호출 실패 - stockCode: {}, error: {}", stockCode, e.getMessage());
            throw new ApiException(MarketErrorCode.KIS_PRICE_FETCH_FAILED);
        }
    }

    // ── 분봉 ────────────────────────────────────────────────────────────────────

    private List<CandleResponse> getMinuteCandles(String accessToken, String stockCode,
                                                    String period, String startDate, String endDate) {
        String today = LocalDate.now(KST).format(DATE_FMT);

        // 다일자 분봉 요청 거절: KIS API는 하루치만 지원
        if (startDate != null && endDate != null && !startDate.equals(endDate)) {
            throw new ApiException(MarketErrorCode.MINUTE_CANDLE_MULTI_DAY_NOT_SUPPORTED);
        }

        // 단일 기준일 결정: startDate → endDate → 오늘 순서로 우선
        String queryDate = startDate != null ? startDate : (endDate != null ? endDate : today);
        boolean isToday = queryDate.equals(today);

        // KIS API는 1분 단위 원시 데이터 반환 → period에 맞게 집계
        List<CandleResponse> rawCandles = isToday
                ? getTodayMinuteCandles(accessToken, stockCode, queryDate)
                : getHistoricalMinuteCandles(accessToken, stockCode, queryDate);

        int periodMinutes = Integer.parseInt(period);
        return aggregateMinuteCandles(rawCandles, periodMinutes);
    }

    /**
     * 1분봉 원시 데이터를 N분봉으로 집계
     *
     * period=1: 그대로 반환
     * period=5: 5개 1분봉 → 1개 5분봉 (open=첫봉, high=최고, low=최저, close=마지막봉, volume=합계)
     * period=60: 60개 1분봉 → 1개 60분봉
     */
    private List<CandleResponse> aggregateMinuteCandles(List<CandleResponse> candles, int periodMinutes) {
        if (periodMinutes == 1 || candles.isEmpty()) {
            return candles;
        }

        // 윈도우 시작 시간(HHmm00) 기준으로 그룹핑 (순서 유지)
        Map<String, List<CandleResponse>> groups = new LinkedHashMap<>();
        for (CandleResponse candle : candles) {
            String windowKey = minuteWindowKey(candle.timestamp(), periodMinutes);
            groups.computeIfAbsent(windowKey, k -> new ArrayList<>()).add(candle);
        }

        return groups.values().stream()
                .map(group -> {
                    CandleResponse first = group.get(0);
                    CandleResponse last = group.get(group.size() - 1);
                    return new CandleResponse(
                            first.timestamp(),
                            first.openPrice(),
                            group.stream().mapToLong(c -> c.highPrice() != null ? c.highPrice() : 0L).max().orElse(0L),
                            // null을 필터링 후 min 계산: 0L 폴백 사용 시 실제 최저가가 0으로 오염되는 버그 방지
                            group.stream().map(CandleResponse::lowPrice).filter(Objects::nonNull).mapToLong(Long::longValue).min().orElse(0L),
                            last.closePrice(),
                            group.stream().mapToLong(c -> c.volume() != null ? c.volume() : 0L).sum()
                    );
                })
                .toList();
    }

    /**
     * HHmmss 형태의 timestamp를 N분 윈도우 시작 시각(HHmm00)으로 변환
     */
    private String minuteWindowKey(String timestamp, int periodMinutes) {
        if (timestamp == null || timestamp.length() < 4) return "000000";
        int hh = Integer.parseInt(timestamp.substring(0, 2));
        int mm = Integer.parseInt(timestamp.substring(2, 4));
        int totalMinutes = hh * 60 + mm;
        int windowStart = (totalMinutes / periodMinutes) * periodMinutes;
        return String.format("%02d%02d00", windowStart / 60, windowStart % 60);
    }

    private List<CandleResponse> getTodayMinuteCandles(String accessToken, String stockCode, String date) {
        String currentTime = LocalTime.now(KST).format(DateTimeFormatter.ofPattern("HHmmss"));

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
                    .header("content-type", "application/json; charset=utf-8")
                    .header("authorization", "Bearer " + accessToken)
                    .header("appkey", kisApiProperties.getAppKey())
                    .header("appsecret", kisApiProperties.getAppSecret())
                    .header("tr_id", TR_INTRADAY)
                    .header("custtype", "P")
                    .retrieve()
                    .body(MinuteChartResponse.class);

            if (response == null || !"0".equals(response.rtCd())) {
                log.error("KIS 당일분봉 조회 실패 - stockCode: {}, msg: {}",
                        stockCode, response != null ? response.msg1() : "null");
                throw new ApiException(MarketErrorCode.KIS_PRICE_FETCH_FAILED);
            }

            if (response.output2() == null) return Collections.emptyList();

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

            return candles.reversed();

        } catch (RestClientException e) {
            log.error("KIS 당일분봉 API 호출 실패 - stockCode: {}, error: {}", stockCode, e.getMessage());
            throw new ApiException(MarketErrorCode.KIS_PRICE_FETCH_FAILED);
        }
    }

    private List<CandleResponse> getHistoricalMinuteCandles(String accessToken, String stockCode, String date) {
        try {
            MinuteChartResponse response = kisRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(MINUTE_CHART_PATH)
                            .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                            .queryParam("FID_INPUT_ISCD", stockCode)
                            .queryParam("FID_INPUT_HOUR_1", "153000")  // 장 마감 시간 기준
                            .queryParam("FID_INPUT_DATE_1", date)
                            .queryParam("FID_PW_DATA_INCU_YN", "Y")
                            .queryParam("FID_FAKE_TICK_INCU_YN", "")
                            .build())
                    .header("content-type", "application/json; charset=utf-8")
                    .header("authorization", "Bearer " + accessToken)
                    .header("appkey", kisApiProperties.getAppKey())
                    .header("appsecret", kisApiProperties.getAppSecret())
                    .header("tr_id", TR_MINUTE)
                    .header("custtype", "P")
                    .retrieve()
                    .body(MinuteChartResponse.class);

            if (response == null || !"0".equals(response.rtCd())) {
                log.error("KIS 일별분봉 조회 실패 - stockCode: {}, msg: {}",
                        stockCode, response != null ? response.msg1() : "null");
                throw new ApiException(MarketErrorCode.KIS_PRICE_FETCH_FAILED);
            }

            if (response.output2() == null) return Collections.emptyList();

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

            return candles.reversed();

        } catch (RestClientException e) {
            log.error("KIS 일별분봉 API 호출 실패 - stockCode: {}, error: {}", stockCode, e.getMessage());
            throw new ApiException(MarketErrorCode.KIS_PRICE_FETCH_FAILED);
        }
    }

    // ── 유틸 ────────────────────────────────────────────────────────────────────

    private String defaultStartDate(String period) {
        return switch (period) {
            case "D" -> LocalDate.now(KST).minusMonths(6).format(DATE_FMT);
            case "W" -> LocalDate.now(KST).minusYears(2).format(DATE_FMT);
            case "M" -> LocalDate.now(KST).minusYears(5).format(DATE_FMT);
            default -> LocalDate.now(KST).format(DATE_FMT);
        };
    }

    private long parseLong(String value) {
        if (value == null || value.isBlank()) return 0L;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.error("KIS 캔들 응답 파싱 실패 - value: '{}'", value);
            return 0L;
        }
    }

    // ── KIS API 응답 파싱용 내부 레코드 ────────────────────────────────────────

    private record DailyChartResponse(
            @JsonProperty("rt_cd") String rtCd,
            @JsonProperty("msg_cd") String msgCd,
            @JsonProperty("msg1") String msg1,
            @JsonProperty("output2") List<DailyOutput> output2
    ) {}

    private record DailyOutput(
            @JsonProperty("stck_bsop_date") String stckBsopDate,  // 영업일자
            @JsonProperty("stck_oprc") String stckOprc,            // 시가
            @JsonProperty("stck_hgpr") String stckHgpr,            // 고가
            @JsonProperty("stck_lwpr") String stckLwpr,            // 저가
            @JsonProperty("stck_clpr") String stckClpr,            // 종가
            @JsonProperty("acml_vol") String acmlVol               // 누적 거래량
    ) {}

    private record MinuteChartResponse(
            @JsonProperty("rt_cd") String rtCd,
            @JsonProperty("msg_cd") String msgCd,
            @JsonProperty("msg1") String msg1,
            @JsonProperty("output2") List<MinuteOutput> output2
    ) {}

    private record MinuteOutput(
            @JsonProperty("stck_cntg_hour") String stckCntgHour,  // 체결시간 (HHmmss)
            @JsonProperty("stck_oprc") String stckOprc,            // 시가
            @JsonProperty("stck_hgpr") String stckHgpr,            // 고가
            @JsonProperty("stck_lwpr") String stckLwpr,            // 저가
            @JsonProperty("stck_prpr") String stckPrpr,            // 현재가 (종가)
            @JsonProperty("cntg_vol") String cntgVol               // 체결 거래량
    ) {}
}
