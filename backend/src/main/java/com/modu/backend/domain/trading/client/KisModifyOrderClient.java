package com.modu.backend.domain.trading.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.modu.backend.domain.trading.entity.OrderModifyAction;
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
 * KIS 주식주문(정정/취소) API 클라이언트
 *
 * POST /uapi/domestic-stock/v1/trading/order-rvsecncl
 * TR_ID: TTTC0013U (실전투자 전용)
 *
 * [정정 (RVSE_CNCL_DVSN_CD = "01")]
 * - QTY_ALL_ORD_YN = "N" 고정
 * - ORD_QTY: 변경할 수량 (없으면 원래 수량 그대로)
 * - ORD_UNPR: 변경할 가격 (없으면 원래 가격 그대로)
 *
 * [취소 (RVSE_CNCL_DVSN_CD = "02")]
 * - QTY_ALL_ORD_YN = "Y" 고정 (미체결 잔량 전량 취소)
 * - ORD_QTY = "0", ORD_UNPR = "0"
 *
 * [KRX_FWDG_ORD_ORGNO]
 * 원 주문 접수 시 KIS 응답에서 받아 orders.kis_org_no 에 저장된 값.
 * null 이면 빈 문자열로 전달 (KIS가 내부적으로 조회).
 *
 * [정정 후 새 주문번호]
 * KIS는 정정/취소 시마다 새로운 odno 와 krx_fwdg_ord_orgno 를 발급.
 * 이후 재정정/취소가 필요할 수 있으므로 반드시 DB에 업데이트해야 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KisModifyOrderClient {

    private static final String MODIFY_PATH = "/uapi/domestic-stock/v1/trading/order-rvsecncl";
    private static final String TR_ID       = "TTTC0013U";

    /** RVSE_CNCL_DVSN_CD: 01=정정, 02=취소 */
    private static final String MODIFY_CODE = "01";
    private static final String CANCEL_CODE = "02";

    /** ord_dvsn: 00=지정가, 01=시장가 */
    private static final String LIMIT_ORD_DVSN  = "00";
    private static final String MARKET_ORD_DVSN = "01";

    private final RestClient kisRestClient;
    private final KisHashKeyClient kisHashKeyClient;

    /**
     * 미체결 주문 정정 또는 취소 실행
     *
     * @param accessToken  KIS Bearer 액세스 토큰
     * @param appKey       복호화된 App Key
     * @param appSecret    복호화된 App Secret
     * @param cano         계좌번호 앞 8자리
     * @param acntPrdtCd   계좌 상품 코드
     * @param kisOrgNo     orders.kis_org_no (KRX_FWDG_ORD_ORGNO, null 허용)
     * @param kisOrderNo   orders.kis_order_no (ORGN_ODNO — 원주문번호)
     * @param orderType    원 주문 방식 (LIMIT/MARKET → ORD_DVSN 변환)
     * @param action       MODIFY(정정) / CANCEL(취소)
     * @param originalQty  원 주문 수량 (정정 시 newQuantity 없으면 사용)
     * @param originalPrice 원 주문 가격 (정정 시 newPrice 없으면 사용)
     * @param newQuantity  변경할 수량 (정정 시, null 이면 원 수량 유지)
     * @param newPrice     변경할 가격 (정정 시, null 이면 원 가격 유지)
     * @return KIS 발급 새 주문번호/KRX조직번호
     */
    public KisModifyResult execute(
            String accessToken, String appKey, String appSecret,
            String cano, String acntPrdtCd,
            String kisOrgNo, String kisOrderNo,
            OrderType orderType, OrderModifyAction action,
            long originalQty, long originalPrice,
            Integer newQuantity, Long newPrice) {

        boolean isCancel = action == OrderModifyAction.CANCEL;
        String rvseCnclDvsnCd = isCancel ? CANCEL_CODE : MODIFY_CODE;
        String ordDvsn        = OrderType.LIMIT == orderType ? LIMIT_ORD_DVSN : MARKET_ORD_DVSN;
        String qtyAllOrdYn    = isCancel ? "Y" : "N";

        // 취소: ORD_QTY=0, ORD_UNPR=0 / 정정: 변경 값 또는 원래 값 유지
        String ordQty  = isCancel ? "0" : String.valueOf(newQuantity  != null ? newQuantity  : originalQty);
        String ordUnpr = isCancel ? "0" : String.valueOf(newPrice     != null ? newPrice     : originalPrice);

        Map<String, String> body = new LinkedHashMap<>();
        body.put("CANO",             cano);
        body.put("ACNT_PRDT_CD",     acntPrdtCd);
        body.put("KRX_FWDG_ORD_ORGNO", kisOrgNo != null ? kisOrgNo : "");
        body.put("ORGN_ODNO",        kisOrderNo);
        body.put("ORD_DVSN",         ordDvsn);
        body.put("RVSE_CNCL_DVSN_CD", rvseCnclDvsnCd);
        body.put("ORD_QTY",          ordQty);
        body.put("ORD_UNPR",         ordUnpr);
        body.put("QTY_ALL_ORD_YN",   qtyAllOrdYn);

        String hashKey = kisHashKeyClient.getHashKey(appKey, appSecret, body);

        try {
            ModifyResponse response = kisRestClient.post()
                    .uri(MODIFY_PATH)
                    .header("content-type", "application/json")
                    .header("authorization", "Bearer " + accessToken)
                    .header("appkey", appKey)
                    .header("appsecret", appSecret)
                    .header("tr_id", TR_ID)
                    .header("custtype", "P")
                    .header("hashkey", hashKey)
                    .body(body)
                    .retrieve()
                    .body(ModifyResponse.class);

            if (response == null || !"0".equals(response.rtCd())) {
                log.error("KIS 주문 정정/취소 실패 - rtCd: {}, msg: {}",
                        response != null ? response.rtCd() : "null",
                        response != null ? response.msg1() : "null");
                throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR);
            }

            // rtCd=0 이지만 output이 없는 경우 — 새 주문번호를 받지 못하면 재정정/취소 불가
            if (response.output() == null) {
                log.error("KIS 주문 정정/취소 성공 응답에 output 없음 - rtCd: {}, msg: {}",
                        response.rtCd(), response.msg1());
                throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR);
            }

            return new KisModifyResult(
                    response.output().odno(),
                    response.output().krxFwdgOrdOrgno()
            );

        } catch (RestClientException e) {
            log.error("KIS 주문 정정/취소 API 호출 실패", e);
            throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR, e);
        }
    }

    // ── KIS API 결과 ────────────────────────────────────────────────────────

    /**
     * 정정/취소 후 KIS가 발급한 새 주문번호
     * 재정정·취소를 위해 DB에 반드시 업데이트해야 한다.
     */
    public record KisModifyResult(
            String newKisOrderNo,   // ODNO — 새 주문번호
            String newKisOrgNo      // KRX_FWDG_ORD_ORGNO — 새 KRX 조직번호
    ) {}

    // ── KIS API 응답 파싱용 내부 레코드 ────────────────────────────────────────

    private record ModifyResponse(
            @JsonProperty("rt_cd") String rtCd,
            @JsonProperty("msg1")  String msg1,
            @JsonProperty("output") ModifyOutput output
    ) {}

    private record ModifyOutput(
            @JsonProperty("KRX_FWDG_ORD_ORGNO") String krxFwdgOrdOrgno, // 새 KRX 조직번호
            @JsonProperty("ODNO")    String odno,                         // 새 주문번호
            @JsonProperty("ORD_TMD") String ordTmd                        // 주문시각
    ) {}
}
