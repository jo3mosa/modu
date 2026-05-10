package com.modu.backend.domain.trading.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.modu.backend.domain.trading.entity.OrderSide;
import com.modu.backend.domain.trading.entity.OrderType;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.error.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * KIS 주문 관련 API 클라이언트
 *
 * [매수가능조회]      GET  /uapi/domestic-stock/v1/trading/inquire-psbl-order  TR: TTTC8908R
 * [매도가능수량조회]   GET  /uapi/domestic-stock/v1/trading/inquire-psbl-sell   TR: TTTC8408R
 * [주식주문(현금)매수] POST /uapi/domestic-stock/v1/trading/order-cash          TR: TTTC0012U
 * [주식주문(현금)매도] POST /uapi/domestic-stock/v1/trading/order-cash          TR: TTTC0011U
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KisOrderClient {

    private static final String ORDER_PATH      = "/uapi/domestic-stock/v1/trading/order-cash";
    private static final String BUY_POWER_PATH  = "/uapi/domestic-stock/v1/trading/inquire-psbl-order";
    private static final String SELL_QTY_PATH   = "/uapi/domestic-stock/v1/trading/inquire-psbl-sell";

    private static final String BUY_TR_ID       = "TTTC0012U";
    private static final String SELL_TR_ID      = "TTTC0011U";
    private static final String BUY_POWER_TR_ID = "TTTC8908R";
    private static final String SELL_QTY_TR_ID  = "TTTC8408R";

    private final RestClient kisRestClient;
    private final KisHashKeyClient kisHashKeyClient;

    /**
     * 매수 가능 금액 조회
     * 잔고 부족 사전 검증에 사용
     */
    public long getBuyableAmount(String accessToken, String appKey, String appSecret,
                                 String cano, String acntPrdtCd, String stockCode, long price) {
        try {
            BuyPowerResponse response = kisRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(BUY_POWER_PATH)
                            .queryParam("CANO", cano)
                            .queryParam("ACNT_PRDT_CD", acntPrdtCd)
                            .queryParam("PDNO", stockCode)
                            .queryParam("ORD_UNPR", String.valueOf(price))
                            .queryParam("ORD_DVSN", "00")
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

            return parseLong(response.output() != null ? response.output().nrcvbBuyAmt() : null);

        } catch (RestClientException e) {
            log.error("KIS 매수가능조회 API 호출 실패: {}", e.getMessage());
            throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR);
        }
    }

    /**
     * 매도 가능 수량 조회
     * 잔고 부족 사전 검증에 사용
     */
    public long getSellableQuantity(String accessToken, String appKey, String appSecret,
                                    String cano, String acntPrdtCd, String stockCode, long price) {
        try {
            SellQtyResponse response = kisRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(SELL_QTY_PATH)
                            .queryParam("CANO", cano)
                            .queryParam("ACNT_PRDT_CD", acntPrdtCd)
                            .queryParam("PDNO", stockCode)
                            .queryParam("ORD_UNPR", String.valueOf(price))
                            .queryParam("ORD_DVSN", "00")
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

            return parseLong(response.output() != null ? response.output().psblQty() : null);

        } catch (RestClientException e) {
            log.error("KIS 매도가능수량조회 API 호출 실패: {}", e.getMessage());
            throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR);
        }
    }

    /**
     * 주식 현금 주문 실행 (매수/매도)
     *
     * @return KisOrderResult (kisOrderNo, kisOrgNo)
     */
    public KisOrderResult placeOrder(String accessToken, String appKey, String appSecret,
                                     String cano, String acntPrdtCd,
                                     String stockCode, OrderSide side, OrderType orderType,
                                     long quantity, long price) {
        String trId    = side == OrderSide.BUY ? BUY_TR_ID : SELL_TR_ID;
        String ordDvsn = orderType == OrderType.LIMIT ? "00" : "01";
        long ordUnpr   = orderType == OrderType.MARKET ? 0L : price;

        Map<String, String> body = new LinkedHashMap<>();
        body.put("CANO", cano);
        body.put("ACNT_PRDT_CD", acntPrdtCd);
        body.put("PDNO", stockCode);
        body.put("ORD_DVSN", ordDvsn);
        body.put("ORD_QTY", String.valueOf(quantity));
        body.put("ORD_UNPR", String.valueOf(ordUnpr));

        String hashKey = kisHashKeyClient.getHashKey(appKey, appSecret, body);

        try {
            PlaceOrderResponse response = kisRestClient.post()
                    .uri(ORDER_PATH)
                    .header("content-type", "application/json; charset=utf-8")
                    .header("authorization", "Bearer " + accessToken)
                    .header("appkey", appKey)
                    .header("appsecret", appSecret)
                    .header("tr_id", trId)
                    .header("custtype", "P")
                    .header("hashkey", hashKey)
                    .body(body)
                    .retrieve()
                    .body(PlaceOrderResponse.class);

            if (response == null || !"0".equals(response.rtCd())) {
                log.error("KIS 주문 실패 - rtCd: {}, msg: {}",
                        response != null ? response.rtCd() : "null",
                        response != null ? response.msg1() : "null");
                throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR);
            }

            return new KisOrderResult(
                    response.output() != null ? response.output().odno() : null,
                    response.output() != null ? response.output().krxFwdgOrdOrgno() : null
            );

        } catch (RestClientException e) {
            log.error("KIS 주문 API 호출 실패: {}", e.getMessage());
            throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR);
        }
    }

    private long parseLong(String value) {
        if (value == null || value.isBlank()) return 0L;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.error("KIS 응답 Long 파싱 실패 - value: '{}'", value);
            return 0L;
        }
    }

    // ── KIS API 결과 ────────────────────────────────────────────────────────

    public record KisOrderResult(String kisOrderNo, String kisOrgNo) {}

    // ── KIS API 응답 파싱용 내부 레코드 ────────────────────────────────────────

    private record BuyPowerResponse(
            @JsonProperty("rt_cd") String rtCd,
            @JsonProperty("msg1") String msg1,
            @JsonProperty("output") BuyPowerOutput output
    ) {}

    private record BuyPowerOutput(
            @JsonProperty("nrcvb_buy_amt") String nrcvbBuyAmt,
            @JsonProperty("psbl_qty") String psblQty
    ) {}

    private record SellQtyResponse(
            @JsonProperty("rt_cd") String rtCd,
            @JsonProperty("msg1") String msg1,
            @JsonProperty("output") SellQtyOutput output
    ) {}

    private record SellQtyOutput(
            @JsonProperty("psbl_qty") String psblQty
    ) {}

    private record PlaceOrderResponse(
            @JsonProperty("rt_cd") String rtCd,
            @JsonProperty("msg1") String msg1,
            @JsonProperty("output") PlaceOrderOutput output
    ) {}

    private record PlaceOrderOutput(
            @JsonProperty("KRX_FWDG_ORD_ORGNO") String krxFwdgOrdOrgno,
            @JsonProperty("ODNO") String odno,
            @JsonProperty("ORD_TMD") String ordTmd
    ) {}
}
