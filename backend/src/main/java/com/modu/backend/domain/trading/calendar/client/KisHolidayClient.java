package com.modu.backend.domain.trading.calendar.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.modu.backend.domain.trading.calendar.entity.TradingCalendar;
import com.modu.backend.global.config.KisApiProperties;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.error.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * KIS 국내휴장일조회 API 클라이언트 — S14P31B106-336
 *
 * GET /uapi/domestic-stock/v1/quotations/chk-holiday  TR_ID=CTCA0903R
 *
 * [페이지네이션]
 *  - 응답 1회당 약 23~24일치 반환
 *  - 응답의 ctx_area_nk / ctx_area_fk 를 다음 요청 query 로 그대로 전달 (trailing space 포함)
 *  - 2번째 호출부터 tr_cont=N 헤더 추가 (연속조회 표시)
 *  - 종료 조건: targetDays 도달 또는 ctx_area_nk 가 공백 / safety 한도 도달
 *
 * [호출 간격]
 *  페이지 간 PAGE_SLEEP_MS 만큼 sleep — KIS 공식 샘플의 smart_sleep 0.1s 대비 보수적 0.5s.
 *  단시간 다수 호출 가이드 보호용.
 *
 * [자격증명]
 *  플랫폼 글로벌 (KisApiProperties.appKey / appSecret). 사용자별 자격 불필요.
 *  토큰은 호출자가 KisPlatformTokenService 에서 받아 전달.
 *
 * [에러 처리]
 *  rt_cd != "0" 또는 RestClientException → CommonErrorCode.EXTERNAL_API_ERROR.
 *  스케줄러는 caller 측에서 catch 해 기존 캐시 유지 + 알람.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KisHolidayClient {

    private static final String PATH = "/uapi/domestic-stock/v1/quotations/chk-holiday";
    private static final String TR_ID = "CTCA0903R";
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 페이지 간 sleep — KIS 1일 1회 가이드 보호용 보수 마진. */
    private static final long PAGE_SLEEP_MS = 500L;
    /** 무한 루프 방지 — targetDays 가 비정상적으로 커도 최대 N페이지에서 종료. */
    private static final int MAX_PAGES = 20;

    private final RestClient kisRestClient;
    private final KisApiProperties kisApiProperties;

    /**
     * from(포함) 부터 약 targetDays 일 이상의 휴장일 데이터를 페이지네이션으로 수집.
     *
     * KIS 응답 페이지에는 from 보다 이전 일자가 포함되지 않으므로 List 순서가 곧 시간 순서.
     * 동일 일자 중복은 응답 페이지 경계에서 발생할 수 있으나 service.upsertAll 이 PK 기준 병합.
     *
     * @return 수집된 TradingCalendar entity 목록 (DB 미저장 상태). 호출자가 upsertAll 로 영속화.
     */
    public List<TradingCalendar> fetchUpcomingHolidays(String accessToken,
                                                       LocalDate from,
                                                       int targetDays) {
        List<TradingCalendar> collected = new ArrayList<>();
        OffsetDateTime fetchedAt = OffsetDateTime.now();
        String ctxNk = "";
        String ctxFk = "";
        boolean firstCall = true;
        int page = 0;

        while (collected.size() < targetDays && page < MAX_PAGES) {
            if (!firstCall) {
                sleepBetweenPages();
            }
            HolidayResponse response = callApi(accessToken, from, ctxNk, ctxFk, !firstCall);
            collected.addAll(toEntities(response, fetchedAt));

            ctxNk = nullToEmpty(response.ctxAreaNk());
            ctxFk = nullToEmpty(response.ctxAreaFk());
            firstCall = false;
            page++;

            // 연속조회 키가 비어있으면 더 이상 페이지 없음 (KIS 종료 신호)
            if (ctxNk.trim().isEmpty()) {
                break;
            }
        }
        log.info("[KIS chk-holiday] 페이지네이션 완료 - from: {}, pages: {}, collected: {}",
                from, page, collected.size());
        return collected;
    }

    // ───────────────────────────────────────────────────────────────────
    // 내부
    // ───────────────────────────────────────────────────────────────────

    private HolidayResponse callApi(String accessToken, LocalDate from,
                                    String ctxNk, String ctxFk, boolean continuation) {
        try {
            return kisRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(PATH)
                            .queryParam("BASS_DT", from.format(YYYYMMDD))
                            .queryParam("CTX_AREA_NK", ctxNk)
                            .queryParam("CTX_AREA_FK", ctxFk)
                            .build())
                    .header("content-type", "application/json; charset=utf-8")
                    .header("authorization", "Bearer " + accessToken)
                    .header("appkey", kisApiProperties.getAppKey())
                    .header("appsecret", kisApiProperties.getAppSecret())
                    .header("tr_id", TR_ID)
                    .header("custtype", "P")
                    .header("tr_cont", continuation ? "N" : "")
                    .retrieve()
                    .body(HolidayResponse.class);
        } catch (RestClientException e) {
            log.error("[KIS chk-holiday] API 호출 실패 - bassDt: {}, continuation: {}",
                    from, continuation, e);
            throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR);
        } finally {
            // 응답 검증은 호출 흐름 안에서 — rt_cd != "0" 인 경우도 ApiException 으로 통일
            // (응답 자체가 null 이면 NPE 방지 위해 별도 처리)
        }
    }

    private List<TradingCalendar> toEntities(HolidayResponse response, OffsetDateTime fetchedAt) {
        if (response == null || !"0".equals(response.rtCd())) {
            String rtCd = response != null ? response.rtCd() : "null";
            String msgCd = response != null ? response.msgCd() : "null";
            String msg = response != null ? response.msg1() : "null";
            log.error("[KIS chk-holiday] 응답 오류 - rtCd: {}, msgCd: {}, msg: {}", rtCd, msgCd, msg);
            throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR);
        }
        List<TradingCalendar> entities = new ArrayList<>();
        if (response.output() == null) {
            return entities;
        }
        for (HolidayOutput out : response.output()) {
            entities.add(TradingCalendar.builder()
                    .bassDt(LocalDate.parse(out.bassDt(), YYYYMMDD))
                    .wdayDvsnCd(out.wdayDvsnCd())
                    .bzdyYn(out.bzdyYn())
                    .trDayYn(out.trDayYn())
                    .opndYn(out.opndYn())
                    .sttlDayYn(out.sttlDayYn())
                    .fetchedAt(fetchedAt)
                    .build());
        }
        return entities;
    }

    private void sleepBetweenPages() {
        try {
            Thread.sleep(PAGE_SLEEP_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    // ───────────────────────────────────────────────────────────────────
    // 응답 DTO
    // ───────────────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record HolidayResponse(
            @JsonProperty("rt_cd")        String rtCd,
            @JsonProperty("msg_cd")       String msgCd,
            @JsonProperty("msg1")         String msg1,
            @JsonProperty("ctx_area_nk")  String ctxAreaNk,
            @JsonProperty("ctx_area_fk")  String ctxAreaFk,
            @JsonProperty("output")       List<HolidayOutput> output
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record HolidayOutput(
            @JsonProperty("bass_dt")      String bassDt,
            @JsonProperty("wday_dvsn_cd") String wdayDvsnCd,
            @JsonProperty("bzdy_yn")      String bzdyYn,
            @JsonProperty("tr_day_yn")    String trDayYn,
            @JsonProperty("opnd_yn")      String opndYn,
            @JsonProperty("sttl_day_yn")  String sttlDayYn
    ) {}
}
