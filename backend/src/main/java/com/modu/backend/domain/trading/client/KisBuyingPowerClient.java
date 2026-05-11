package com.modu.backend.domain.trading.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.error.CommonErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * KIS 주문 가능 금액/수량 조회 클라이언트
 *
 * [매수가능조회]      GET /uapi/domestic-stock/v1/trading/inquire-psbl-order  TR: TTTC8908R
 * [매도가능수량조회]   GET /uapi/domestic-stock/v1/trading/inquire-psbl-sell   TR: TTTC8408R
 *
 * [필드 매핑]
 * inquire-psbl-order:
 *   - nrcvb_buy_amt → maxBuyAmount  (미수 없는 최대 매수 가능 금액)
 *   - ord_psbl_cash → availableCash (주문 가능 현금 — 미체결 주문 차감 후 실사용 가능 금액)
 *
 * inquire-psbl-sell:
 *   - ord_psbl_qty  → maxSellQuantity (주문 가능 수량)
 *
 * [orderPrice null 처리]
 * orderPrice 미전달 시 ORD_UNPR="0", ORD_DVSN="01"(시장가 기준)으로 조회
 */
@Slf4j
@Component
public class KisBuyingPowerClient {

    private static final String BUY_POWER_PATH = "/uapi/domestic-stock/v1/trading/inquire-psbl-order";
    private static final String SELL_QTY_PATH  = "/uapi/domestic-stock/v1/trading/inquire-psbl-sell";

    private static final String BUY_POWER_TR_ID = "TTTC8908R";
    private static final String SELL_QTY_TR_ID  = "TTTC8408R";

    /** orderPrice 미전달 시 ORD_DVSN: 01(시장가) 기준으로 조회 */
    private static final String MARKET_ORD_DVSN = "01";
    private static final String LIMIT_ORD_DVSN  = "00";

    private final RestClient kisRestClient;

    public KisBuyingPowerClient(RestClient kisRestClient) {
        this.kisRestClient = kisRestClient;
    }

    /**
     * 매수 가능 금액 + 주문 가능 현금 조회
     *
     * @param stockCode  종목코드
     * @param orderPrice 주문 희망 가격 (null 이면 시장가 기준으로 조회)
     * @return KisBuyPowerInfo (maxBuyAmount, availableCash)
     */
    public KisBuyPowerInfo getBuyPowerInfo(String accessToken, String appKey, String appSecret,
                                           String cano, String acntPrdtCd,
                                           String stockCode, Long orderPrice) {
        String ordUnpr = orderPrice != null ? String.valueOf(orderPrice) : "0";
        String ordDvsn = orderPrice != null ? LIMIT_ORD_DVSN : MARKET_ORD_DVSN;

        try {
            BuyPowerResponse response = kisRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(BUY_POWER_PATH)
                            .queryParam("CANO", cano)
                            .queryParam("ACNT_PRDT_CD", acntPrdtCd)
                            .queryParam("PDNO", stockCode)
                            .queryParam("ORD_UNPR", ordUnpr)
                            .queryParam("ORD_DVSN", ordDvsn)
                            .queryParam("CMA_EVLU_AMT_ICLD_YN", "N")
                            .queryParam("OVRS_ICLD_YN", "N")
                            .build())
                    .header("content-type", "application/json; charset=utf-8")
                    .header("authorization", "Bearer " + accessToken)
                    .header("appkey", appKey)
                    .header("appsecret", appSecret)
                    .header("tr_id", BUY_POWER_TR_ID)
                    .header("custtype", "P")
                    .retrieve()
                    .body(BuyPowerResponse.class);

            if (response == null || !"0".equals(response.rtCd())) {
                log.error("KIS 매수가능조회 실패 - rtCd: {}, msg: {}",
                        response != null ? response.rtCd() : "null",
                        response != null ? response.msg1() : "null");
                throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR);
            }

            if (response.output() == null) {
                log.error("KIS 매수가능조회 성공 응답에 output 없음");
                throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR);
            }

            return new KisBuyPowerInfo(
                    parseLong(response.output().nrcvbBuyAmt()),
                    parseLong(response.output().nrcvbBuyQty()),
                    parseLong(response.output().ordPsblCash())
            );

        } catch (RestClientException e) {
            log.error("KIS 매수가능조회 API 호출 실패", e);
            throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR, e);
        }
    }

    /**
     * 매도 가능 수량 조회
     *
     * @param stockCode 종목코드 (가격 파라미터 불필요 — KIS 스펙상 PDNO 만 필요)
     * @return 매도 가능 수량
     */
    public long getSellableQuantity(String accessToken, String appKey, String appSecret,
                                    String cano, String acntPrdtCd, String stockCode) {
        try {
            SellQtyResponse response = kisRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(SELL_QTY_PATH)
                            .queryParam("CANO", cano)
                            .queryParam("ACNT_PRDT_CD", acntPrdtCd)
                            .queryParam("PDNO", stockCode)
                            .build())
                    .header("content-type", "application/json; charset=utf-8")
                    .header("authorization", "Bearer " + accessToken)
                    .header("appkey", appKey)
                    .header("appsecret", appSecret)
                    .header("tr_id", SELL_QTY_TR_ID)
                    .header("custtype", "P")
                    .retrieve()
                    .body(SellQtyResponse.class);

            if (response == null || !"0".equals(response.rtCd())) {
                log.error("KIS 매도가능수량조회 실패 - rtCd: {}, msg: {}",
                        response != null ? response.rtCd() : "null",
                        response != null ? response.msg1() : "null");
                throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR);
            }

            if (response.output() == null) {
                log.error("KIS 매도가능수량조회 성공 응답에 output 없음");
                throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR);
            }

            return parseLong(response.output().ordPsblQty());

        } catch (RestClientException e) {
            log.error("KIS 매도가능수량조회 API 호출 실패", e);
            throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR, e);
        }
    }

    /**
     * KIS 응답 숫자 문자열 파싱
     * null/blank: 0 반환 / 숫자 아닌 값: EXTERNAL_API_ERROR (cause 보존)
     */
    private long parseLong(String value) {
        if (value == null || value.isBlank()) return 0L;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.error("KIS 응답 파싱 실패 - 숫자로 변환 불가한 값: '{}'", value, e);
            throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR, e);
        }
    }

    // ── KIS API 결과 ────────────────────────────────────────────────────────

    /**
     * 매수가능조회 결과
     * maxBuyAmount:  미수 없는 최대 매수 가능 금액 (nrcvb_buy_amt)
     * maxBuyQuantity: 미수 없는 최대 매수 가능 수량 (nrcvb_buy_qty)
     * availableCash: 주문 가능 현금 (ord_psbl_cash) — 미체결 주문 차감 후 실사용 가능 금액
     */
    public record KisBuyPowerInfo(long maxBuyAmount, long maxBuyQuantity, long availableCash) {}

    // ── KIS API 응답 파싱용 내부 레코드 ────────────────────────────────────────

    private record BuyPowerResponse(
            @JsonProperty("rt_cd") String rtCd,
            @JsonProperty("msg1")  String msg1,
            @JsonProperty("output") BuyPowerOutput output
    ) {}

    private record BuyPowerOutput(
            @JsonProperty("nrcvb_buy_amt") String nrcvbBuyAmt,  // 미수없는 매수 가능 금액
            @JsonProperty("nrcvb_buy_qty") String nrcvbBuyQty,  // 미수없는 매수 가능 수량
            @JsonProperty("ord_psbl_cash") String ordPsblCash   // 주문 가능 현금 (예수금 기반)
    ) {}

    private record SellQtyResponse(
            @JsonProperty("rt_cd") String rtCd,
            @JsonProperty("msg1")  String msg1,
            @JsonProperty("output") SellQtyOutput output
    ) {}

    private record SellQtyOutput(
            @JsonProperty("ord_psbl_qty") String ordPsblQty  // 주문 가능 수량
    ) {}
}
