package com.modu.backend.domain.trading.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.error.CommonErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * KIS 주식일별주문체결조회 API 클라이언트
 *
 * GET /uapi/domestic-stock/v1/trading/inquire-daily-ccld
 * TR_ID: TTTC0081R (3개월 이내), CTSC9215R (3개월 이상)
 *
 * [용도]
 * 사용자의 전체 거래 이력(체결/미체결/취소 포함)을 기간 단위로 조회한다.
 * KIS HTS/앱 직접 주문 케이스도 포함되어 우리 시스템 외부 주문 이력까지 수집 가능.
 *
 * [TR_ID 자동 분기]
 * 조회 기간이 3개월 이내면 TTTC0081R(100건/페이지), 초과 시 CTSC9215R(15건/페이지).
 *
 * [연속조회]
 * CTX_AREA_FK100/NK100 키로 모든 페이지를 누적 수집한 뒤 반환.
 * 무한루프 방지용 최대 반복 횟수 가드(200회) 적용.
 *
 * [필드 매핑] KIS output1 → KisHistoryItem
 * - odno            → odno         (주문번호, orders.kis_order_no 매칭 키)
 * - pdno            → stockCode    (종목코드)
 * - prdt_name       → stockName    (종목명)
 * - sll_buy_dvsn_cd → side         (01=SELL, 02=BUY)
 * - ord_dvsn_cd     → orderType    (00=LIMIT, 그 외=MARKET)
 * - ord_qty         → quantity     (주문수량)
 * - ord_unpr        → price        (주문단가)
 * - ord_dt+ord_tmd  → orderedAt    (DB 미매칭 시 createdAt 으로 사용)
 * - tot_ccld_qty, rmn_qty, cncl_yn → status 유추
 */
@Slf4j
@Component
public class KisOrderHistoryClient {

    private static final String HISTORY_PATH = "/uapi/domestic-stock/v1/trading/inquire-daily-ccld";

    /** 3개월 이내 조회 TR_ID (실전, 100건/페이지) */
    private static final String TR_ID_RECENT = "TTTC0081R";
    /** 3개월 이상 조회 TR_ID (실전, 15건/페이지) */
    private static final String TR_ID_PAST   = "CTSC9215R";

    /** TR_ID 분기 기준 — 3개월 */
    private static final int RECENT_PERIOD_MONTHS = 3;

    /**
     * 연속조회 진행 없음(같은 NK100 키 반복) 허용 횟수 — 비정상 무한루프 방지용 가드
     * KIS 응답이 안정적이라면 정상 흐름에서 동일 키가 연속 등장할 일이 없으므로 작은 값으로 충분
     */
    private static final int NO_PROGRESS_THRESHOLD = 3;

    /** sll_buy_dvsn_cd: 01=매도(SELL), 02=매수(BUY) */
    private static final String SELL_CODE = "01";
    private static final String BUY_CODE  = "02";

    /** ord_dvsn_cd: 00=지정가(LIMIT), 그 외=MARKET */
    private static final String LIMIT_CODE = "00";

    /** cncl_yn = "Y" 이면 취소 주문 */
    private static final String CANCEL_FLAG = "Y";

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HHmmss");

    private final RestClient kisRestClient;

    /**
     * 연속조회 최대 페이지 수 — 설정값 (default 2000)
     * CTSC9215R(15건/페이지) 기준 30,000건까지 수용. 그 이상 필요한 계좌는 설정으로 상향.
     */
    private final int maxPageFetch;

    public KisOrderHistoryClient(RestClient kisRestClient,
                                 @Value("${kis.order-history.max-page-fetch:2000}") int maxPageFetch) {
        // 0 이하 값이면 루프가 한 번도 돌지 않아 모든 요청이 조용히 실패 — 빈 컨테이너 기동 시점에 차단
        if (maxPageFetch <= 0) {
            throw new IllegalArgumentException(
                    "kis.order-history.max-page-fetch must be > 0, but was: " + maxPageFetch);
        }
        this.kisRestClient = kisRestClient;
        this.maxPageFetch = maxPageFetch;
    }

    /**
     * 기간 내 전체 주문 이력 조회 (연속조회 자동 처리)
     *
     * @param accessToken KIS Bearer 액세스 토큰
     * @param appKey      복호화된 App Key
     * @param appSecret   복호화된 App Secret
     * @param cano        계좌번호 앞 8자리
     * @param acntPrdtCd  계좌 상품 코드
     * @param from        조회 시작일
     * @param to          조회 종료일
     * @return 전체 페이지 누적 결과. 없으면 빈 리스트
     */
    public List<KisHistoryItem> getOrderHistory(String accessToken, String appKey, String appSecret,
                                                String cano, String acntPrdtCd,
                                                LocalDate from, LocalDate to) {
        String trId = resolveTrId(from, to);
        String fromYmd = from.format(YMD);
        String toYmd   = to.format(YMD);

        List<KisHistoryItem> accumulated = new ArrayList<>();
        String ctxFk = "";
        String ctxNk = "";

        // 진행 없음(같은 NK100 키 반복) 감지용 추적값
        String lastNk = null;
        int noProgressCount = 0;

        for (int page = 0; page < maxPageFetch; page++) {
            HistoryResponse response = callKis(accessToken, appKey, appSecret,
                    cano, acntPrdtCd, fromYmd, toYmd, trId, ctxFk, ctxNk);

            if (response.output1() != null) {
                for (RawHistoryItem raw : response.output1()) {
                    accumulated.add(toKisHistoryItem(raw));
                }
            }

            // 연속조회 키가 비어있으면 종료
            String nextFk = response.ctxAreaFk100();
            String nextNk = response.ctxAreaNk100();
            if (nextNk == null || nextNk.isBlank()) {
                return accumulated;
            }
            String trimmedNk = nextNk.trim();

            // 같은 NK100 이 반복 등장 → 진행 없음. 임계치 초과 시 무한루프로 간주
            if (trimmedNk.equals(lastNk)) {
                noProgressCount++;
                if (noProgressCount >= NO_PROGRESS_THRESHOLD) {
                    log.error("KIS 거래 이력 연속조회 진행 없음 감지 - cano: {}, from: {}, to: {}",
                            maskCano(cano), fromYmd, toYmd);
                    throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR);
                }
            } else {
                noProgressCount = 0;
            }
            lastNk = trimmedNk;

            ctxFk = nextFk == null ? "" : nextFk.trim();
            ctxNk = trimmedNk;
        }

        log.error("KIS 거래 이력 연속조회 최대 반복 횟수 초과 - cano: {}, from: {}, to: {}, maxPageFetch: {}",
                maskCano(cano), fromYmd, toYmd, maxPageFetch);
        throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR);
    }

    /**
     * 계좌번호 마스킹 — 로그 출력용
     * 8자리 cano 의 끝 4자리만 표시. null/짧은 값은 안전하게 처리.
     */
    private String maskCano(String cano) {
        if (cano == null || cano.isBlank()) return "****";
        int len = cano.length();
        if (len <= 4) return "*".repeat(len);
        return "*".repeat(len - 4) + cano.substring(len - 4);
    }

    private HistoryResponse callKis(String accessToken, String appKey, String appSecret,
                                    String cano, String acntPrdtCd,
                                    String fromYmd, String toYmd, String trId,
                                    String ctxFk, String ctxNk) {
        try {
            HistoryResponse response = kisRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(HISTORY_PATH)
                            .queryParam("CANO", cano)
                            .queryParam("ACNT_PRDT_CD", acntPrdtCd)
                            .queryParam("INQR_STRT_DT", fromYmd)
                            .queryParam("INQR_END_DT", toYmd)
                            .queryParam("SLL_BUY_DVSN_CD", "00")    // 전체(매수+매도)
                            .queryParam("INQR_DVSN", "00")           // 역순
                            .queryParam("PDNO", "")                  // 종목 미지정
                            .queryParam("CCLD_DVSN", "00")           // 체결+미체결
                            .queryParam("ORD_GNO_BRNO", "")
                            .queryParam("ODNO", "")
                            .queryParam("INQR_DVSN_3", "00")         // 전체
                            .queryParam("INQR_DVSN_1", "")           // 전체
                            .queryParam("EXCG_ID_DVSN_CD", "KRX")
                            .queryParam("CTX_AREA_FK100", ctxFk)
                            .queryParam("CTX_AREA_NK100", ctxNk)
                            .build())
                    .header("content-type", "application/json; charset=utf-8")
                    .header("authorization", "Bearer " + accessToken)
                    .header("appkey", appKey)
                    .header("appsecret", appSecret)
                    .header("tr_id", trId)
                    .header("tr_cont", ctxNk.isBlank() ? "" : "N") // 연속조회 시 "N"
                    .header("custtype", "P")
                    .retrieve()
                    .body(HistoryResponse.class);

            if (response == null || !"0".equals(response.rtCd())) {
                log.error("KIS 거래 이력 조회 실패 - rtCd: {}, msg: {}",
                        response != null ? response.rtCd() : "null",
                        response != null ? response.msg1() : "null");
                throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR);
            }
            return response;

        } catch (RestClientException e) {
            log.error("KIS 거래 이력 조회 API 호출 실패", e);
            throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR, e);
        }
    }

    /**
     * from~to 기간 길이에 따라 TR_ID 결정
     * 3개월 이내: TTTC0081R (100건/페이지)
     * 3개월 초과: CTSC9215R (15건/페이지)
     */
    private String resolveTrId(LocalDate from, LocalDate to) {
        LocalDate threshold = to.minusMonths(RECENT_PERIOD_MONTHS);
        // from 이 threshold 이전이면 3개월 초과로 간주
        return from.isBefore(threshold) ? TR_ID_PAST : TR_ID_RECENT;
    }

    /**
     * KIS raw 응답 → 타입 변환된 KisHistoryItem
     */
    private KisHistoryItem toKisHistoryItem(RawHistoryItem raw) {
        long ordQty     = parseLong(raw.ordQty());
        long totCcldQty = parseLong(raw.totCcldQty());
        long rmnQty     = parseLong(raw.rmnQty());

        return new KisHistoryItem(
                raw.odno(),
                raw.pdno(),
                raw.prdtName(),
                parseSide(raw.sllBuyDvsnCd()),
                LIMIT_CODE.equals(raw.ordDvsnCd()) ? "LIMIT" : "MARKET",
                ordQty,
                parseLong(raw.ordUnpr()),
                resolveStatus(raw.cnclYn(), totCcldQty, rmnQty),
                parseOrderedAt(raw.ordDt(), raw.ordTmd())
        );
    }

    /**
     * status 유추 로직
     *  cncl_yn = "Y"                 → CANCELED
     *  tot_ccld_qty > 0 && rmn_qty==0 → FILLED
     *  그 외                          → PENDING (미체결 또는 부분체결)
     */
    private String resolveStatus(String cnclYn, long totCcldQty, long rmnQty) {
        if (CANCEL_FLAG.equals(cnclYn)) return "CANCELED";
        if (totCcldQty > 0 && rmnQty == 0) return "FILLED";
        return "PENDING";
    }

    /**
     * sll_buy_dvsn_cd → BUY/SELL 변환
     * 허용 값: 01(매도), 02(매수). 그 외는 KIS 포맷 오류로 EXTERNAL_API_ERROR.
     */
    private String parseSide(String sllBuyDvsnCd) {
        if (SELL_CODE.equals(sllBuyDvsnCd)) return "SELL";
        if (BUY_CODE.equals(sllBuyDvsnCd))  return "BUY";
        log.error("KIS 거래 이력 응답 sll_buy_dvsn_cd 유효하지 않은 값: '{}'", sllBuyDvsnCd);
        throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR);
    }

    /**
     * ord_dt(YYYYMMDD) + ord_tmd(HHMMSS) → LocalDateTime
     * 일자/시각 누락 또는 파싱 실패 시 null 반환 (서비스에서 createdAt 대체값 처리)
     */
    private LocalDateTime parseOrderedAt(String ordDt, String ordTmd) {
        if (ordDt == null || ordDt.isBlank()) return null;
        try {
            LocalDate date = LocalDate.parse(ordDt.trim(), YMD);
            LocalTime time = (ordTmd == null || ordTmd.isBlank())
                    ? LocalTime.MIDNIGHT
                    : LocalTime.parse(ordTmd.trim(), HMS);
            return LocalDateTime.of(date, time);
        } catch (DateTimeParseException e) {
            log.warn("KIS 거래 이력 ord_dt/ord_tmd 파싱 실패 - ord_dt: '{}', ord_tmd: '{}'", ordDt, ordTmd);
            return null;
        }
    }

    /**
     * KIS 응답 숫자 문자열 파싱
     * null/blank: 0L. 숫자 변환 실패: EXTERNAL_API_ERROR (cause 보존)
     */
    private long parseLong(String value) {
        if (value == null || value.isBlank()) return 0L;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.error("KIS 거래 이력 응답 파싱 실패 - 숫자로 변환 불가한 값 수신: '{}'", value, e);
            throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR, e);
        }
    }

    // ── 외부 공개용 결과 레코드 ─────────────────────────────────────────────────

    /**
     * 타입 변환 완료된 거래 이력 항목
     * side/orderType/status 는 도메인 값으로 변환된 상태
     */
    public record KisHistoryItem(
            String odno,
            String stockCode,
            String stockName,
            String side,           // BUY / SELL
            String orderType,      // LIMIT / MARKET
            long quantity,
            long price,
            String status,         // FILLED / CANCELED / PENDING
            LocalDateTime orderedAt
    ) {
        public static List<KisHistoryItem> emptyList() {
            return Collections.emptyList();
        }
    }

    // ── KIS API 응답 파싱용 내부 레코드 ─────────────────────────────────────────

    private record HistoryResponse(
            @JsonProperty("rt_cd") String rtCd,
            @JsonProperty("msg1") String msg1,
            @JsonProperty("ctx_area_fk100") String ctxAreaFk100,
            @JsonProperty("ctx_area_nk100") String ctxAreaNk100,
            @JsonProperty("output1") List<RawHistoryItem> output1
    ) {}

    private record RawHistoryItem(
            @JsonProperty("ord_dt") String ordDt,                  // 주문일자 YYYYMMDD
            @JsonProperty("ord_tmd") String ordTmd,                // 주문시각 HHMMSS
            @JsonProperty("odno") String odno,                     // 주문번호
            @JsonProperty("pdno") String pdno,                     // 종목코드
            @JsonProperty("prdt_name") String prdtName,            // 종목명
            @JsonProperty("sll_buy_dvsn_cd") String sllBuyDvsnCd,  // 01:매도, 02:매수
            @JsonProperty("ord_dvsn_cd") String ordDvsnCd,         // 00:지정가, 그 외:시장가 등
            @JsonProperty("ord_qty") String ordQty,                // 주문수량
            @JsonProperty("ord_unpr") String ordUnpr,              // 주문단가
            @JsonProperty("tot_ccld_qty") String totCcldQty,       // 총체결수량
            @JsonProperty("rmn_qty") String rmnQty,                // 잔여수량
            @JsonProperty("cncl_yn") String cnclYn                 // 취소여부 (Y/공백)
    ) {}
}
