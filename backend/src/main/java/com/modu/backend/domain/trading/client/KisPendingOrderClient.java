package com.modu.backend.domain.trading.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.error.CommonErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Collections;
import java.util.List;

/**
 * KIS 주식정정취소가능주문조회 API 클라이언트
 *
 * GET /uapi/domestic-stock/v1/trading/inquire-psbl-rvsecncl
 * TR_ID: TTTC0084R (실전투자 전용)
 *
 * [용도]
 * 사용자의 현재 미체결 주문 목록을 실시간으로 조회한다.
 * 단건 호출로 최대 50건까지 조회되며, 초과 시 CTX_AREA_FK100/NK100 연속조회 키로 페이지네이션 가능.
 * MVP 단계에서는 첫 페이지(최대 50건)만 조회한다.
 *
 * [Request 파라미터]
 * INQR_DVSN_1 = "0" : 주문 기준 조회 (vs "1": 잔고 기준)
 * INQR_DVSN_2 = "0" : 전체 조회 (vs "1": 매도만, "2": 매수만)
 *
 * [필드 매핑] KIS output → KisPendingItem
 * - odno            → odno         (주문번호, orders.kis_order_no 와 조인 키)
 * - pdno            → stockCode    (종목코드)
 * - prdt_name       → stockName    (종목명)
 * - sll_buy_dvsn_cd → side         (01=SELL, 02=BUY)
 * - ord_dvsn_cd     → orderType    (00=LIMIT, 그 외=MARKET)
 * - ord_qty         → quantity     (주문수량)
 * - ord_unpr        → price        (주문단가)
 * - tot_ccld_qty    → filledQty    (총체결수량 — 계산값 아닌 실제 필드)
 * - psbl_qty        → remainQty    (미체결잔량 = 정정/취소 가능 수량)
 */
@Slf4j
@Component
public class KisPendingOrderClient {

    private static final String PENDING_PATH   = "/uapi/domestic-stock/v1/trading/inquire-psbl-rvsecncl";
    private static final String TR_ID          = "TTTC0084R";

    /** sll_buy_dvsn_cd: 01=매도, 02=매수 */
    private static final String SELL_CODE = "01";

    /** ord_dvsn_cd: 00=지정가, 그 외(01 시장가 등)=MARKET 으로 통일 처리 */
    private static final String LIMIT_CODE = "00";

    private final RestClient kisRestClient;

    public KisPendingOrderClient(RestClient kisRestClient) {
        this.kisRestClient = kisRestClient;
    }

    /**
     * 미체결 주문 목록 조회
     *
     * @param accessToken KIS Bearer 액세스 토큰
     * @param appKey      복호화된 App Key
     * @param appSecret   복호화된 App Secret
     * @param cano        계좌번호 앞 8자리
     * @param acntPrdtCd  계좌 상품 코드
     * @return 미체결 주문 목록. 미체결 없으면 빈 리스트 반환
     */
    public List<KisPendingItem> getPendingOrders(String accessToken, String appKey, String appSecret,
                                                  String cano, String acntPrdtCd) {
        try {
            PendingResponse response = kisRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(PENDING_PATH)
                            .queryParam("CANO", cano)
                            .queryParam("ACNT_PRDT_CD", acntPrdtCd)
                            .queryParam("CTX_AREA_FK100", "")  // 첫 조회 시 공백
                            .queryParam("CTX_AREA_NK100", "")  // 첫 조회 시 공백
                            .queryParam("INQR_DVSN_1", "0")   // 주문 기준 조회
                            .queryParam("INQR_DVSN_2", "0")   // 매수+매도 전체
                            .build())
                    .header("content-type", "application/json; charset=utf-8")
                    .header("authorization", "Bearer " + accessToken)
                    .header("appkey", appKey)
                    .header("appsecret", appSecret)
                    .header("tr_id", TR_ID)
                    .header("custtype", "P")
                    .retrieve()
                    .body(PendingResponse.class);

            if (response == null || !"0".equals(response.rtCd())) {
                log.error("KIS 미체결 주문 조회 실패 - rtCd: {}, msg: {}",
                        response != null ? response.rtCd() : "null",
                        response != null ? response.msg1() : "null");
                throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR);
            }

            if (response.output() == null) {
                return Collections.emptyList();
            }

            // KIS 응답 raw 데이터를 타입 변환된 KisPendingItem 으로 매핑
            return response.output().stream()
                    .map(this::toKisPendingItem)
                    .toList();

        } catch (RestClientException e) {
            log.error("KIS 미체결 주문 조회 API 호출 실패: {}", e.getMessage());
            throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR);
        }
    }

    /**
     * KIS raw 응답 레코드 → 타입 변환된 KisPendingItem 변환
     * sll_buy_dvsn_cd, ord_dvsn_cd 를 도메인 값(BUY/SELL, LIMIT/MARKET)으로 변환
     */
    private KisPendingItem toKisPendingItem(RawPendingItem raw) {
        return new KisPendingItem(
                raw.odno(),
                raw.pdno(),
                raw.prdtName(),
                SELL_CODE.equals(raw.sllBuyDvsnCd()) ? "SELL" : "BUY",
                LIMIT_CODE.equals(raw.ordDvsnCd()) ? "LIMIT" : "MARKET",
                parseLong(raw.ordQty()),
                parseLong(raw.ordUnpr()),
                parseLong(raw.totCcldQty()),
                parseLong(raw.psblQty())
        );
    }

    /**
     * KIS 응답 숫자 문자열 파싱
     * null/blank: 0 반환 (미제공 필드)
     * 숫자가 아닌 값: KIS API 포맷 오류로 EXTERNAL_API_ERROR 처리
     */
    private long parseLong(String value) {
        if (value == null || value.isBlank()) return 0L;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.error("KIS 미체결 주문 응답 파싱 실패 - 숫자로 변환 불가한 값 수신: '{}'", value);
            throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR);
        }
    }

    // ── 외부 공개용 결과 레코드 ─────────────────────────────────────────────────

    /**
     * 타입 변환 완료된 미체결 주문 항목
     * side, orderType 은 도메인 값(BUY/SELL, LIMIT/MARKET)으로 변환된 상태
     */
    public record KisPendingItem(
            String odno,            // 주문번호 (orders.kis_order_no 조인 키)
            String stockCode,       // 종목코드
            String stockName,       // 종목명
            String side,            // BUY / SELL
            String orderType,       // LIMIT / MARKET
            long quantity,          // 주문수량
            long price,             // 주문단가
            long filledQuantity,    // 총체결수량
            long remainQuantity     // 미체결잔량 (정정/취소 가능 수량)
    ) {}

    // ── KIS API 응답 파싱용 내부 레코드 ─────────────────────────────────────────

    private record PendingResponse(
            @JsonProperty("rt_cd") String rtCd,
            @JsonProperty("msg1") String msg1,
            @JsonProperty("output") List<RawPendingItem> output
    ) {}

    private record RawPendingItem(
            @JsonProperty("odno") String odno,                    // 주문번호
            @JsonProperty("pdno") String pdno,                    // 종목코드
            @JsonProperty("prdt_name") String prdtName,           // 종목명
            @JsonProperty("sll_buy_dvsn_cd") String sllBuyDvsnCd, // 매도매수구분코드 (01:매도, 02:매수)
            @JsonProperty("ord_dvsn_cd") String ordDvsnCd,        // 주문구분코드 (00:지정가, 01:시장가 등)
            @JsonProperty("ord_qty") String ordQty,               // 주문수량
            @JsonProperty("ord_unpr") String ordUnpr,             // 주문단가
            @JsonProperty("tot_ccld_qty") String totCcldQty,      // 총체결수량
            @JsonProperty("psbl_qty") String psblQty,             // 미체결잔량
            @JsonProperty("ord_gno_brno") String ordGnoBrno       // 주문채번지점번호 (정정/취소 API에서 사용)
    ) {}
}
