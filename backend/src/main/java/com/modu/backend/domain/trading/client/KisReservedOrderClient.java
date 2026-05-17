package com.modu.backend.domain.trading.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.modu.backend.domain.trading.entity.OrderSide;
import com.modu.backend.domain.trading.entity.OrderType;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.error.CommonErrorCode;
import com.modu.backend.global.kis.KisErrorMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * KIS 주식 예약주문 API 클라이언트 — S14P31B106-336
 *
 * POST /uapi/domestic-stock/v1/trading/order-resv  TR_ID=CTSC0008U
 *
 * [모의투자 미지원]
 *  KIS 예약주문은 실전 계좌 전용. 호출자(KisOrderConsumer)가 사전에 is_real_account 검증 후 진입해야 한다.
 *
 * [매도/매수 구분]
 *  SLL_BUY_DVSN_CD — 01=매도, 02=매수. 일반 주문 API 와 달리 단일 엔드포인트라 본 필드로 구분.
 *
 * [주문대상잔고구분]
 *  ORD_OBJT_CBLC_DVSN_CD — 10 (현금) 고정. 신용/대주는 본 시스템 범위 외.
 *
 * [예약주문 종료일자]
 *  RSVN_ORD_END_DT — 빈 문자열. KIS 가 "일반예약주문" 으로 분류해 다음 거래일에 1회 실행 후 종료.
 *  공휴일이 끼면 KIS 가 다음 거래일로 자동 롤포워드.
 *
 * [Content-Type]
 *  KIS 명세 권장값 application/json; charset=utf-8 명시.
 *  (기존 KisOrderClient/KisModifyOrderClient 는 일관성 부족 — 별도 통일 이슈로 분리)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KisReservedOrderClient {

    private static final String PATH = "/uapi/domestic-stock/v1/trading/order-resv";
    private static final String TR_ID = "CTSC0008U";

    /** SLL_BUY_DVSN_CD */
    private static final String SELL_CODE = "01";
    private static final String BUY_CODE  = "02";

    /** ORD_DVSN_CD */
    private static final String LIMIT_ORD_DVSN  = "00";
    private static final String MARKET_ORD_DVSN = "01";

    /** ORD_OBJT_CBLC_DVSN_CD — 현금 고정 */
    private static final String CASH_OBJT_CBLC = "10";

    private final RestClient kisRestClient;
    private final KisHashKeyClient kisHashKeyClient;

    /**
     * 예약주문 접수.
     *
     * @return RSVN_ORD_SEQ 를 담은 결과. 동일 값을 orders.kis_rsvn_seq 에 저장한다.
     */
    public KisReservedOrderResult placeReservedOrder(
            String accessToken, String appKey, String appSecret,
            String cano, String acntPrdtCd,
            String stockCode, OrderSide side, OrderType orderType,
            long quantity, long price) {

        String sllBuyCd = side == OrderSide.BUY ? BUY_CODE : SELL_CODE;
        String ordDvsn  = orderType == OrderType.LIMIT ? LIMIT_ORD_DVSN : MARKET_ORD_DVSN;
        long ordUnpr    = orderType == OrderType.MARKET ? 0L : price;

        Map<String, String> body = new LinkedHashMap<>();
        body.put("CANO",                  cano);
        body.put("ACNT_PRDT_CD",          acntPrdtCd);
        body.put("PDNO",                  stockCode);
        body.put("ORD_QTY",               String.valueOf(quantity));
        body.put("ORD_UNPR",              String.valueOf(ordUnpr));
        body.put("SLL_BUY_DVSN_CD",       sllBuyCd);
        body.put("ORD_DVSN_CD",           ordDvsn);
        body.put("ORD_OBJT_CBLC_DVSN_CD", CASH_OBJT_CBLC);
        body.put("LOAN_DT",               "");
        body.put("RSVN_ORD_END_DT",       "");

        String hashKey = kisHashKeyClient.getHashKey(appKey, appSecret, body);

        log.info("[예약주문] KIS 호출 - cano: {}, stockCode: {}, side: {}, ordDvsn: {}, ordQty: {}, ordUnpr: {}",
                cano, stockCode, side, ordDvsn, quantity, ordUnpr);

        try {
            ReservedOrderResponse response = kisRestClient.post()
                    .uri(PATH)
                    .header("content-type", "application/json; charset=utf-8")
                    .header("authorization", "Bearer " + accessToken)
                    .header("appkey", appKey)
                    .header("appsecret", appSecret)
                    .header("tr_id", TR_ID)
                    .header("custtype", "P")
                    .header("hashkey", hashKey)
                    .body(body)
                    .retrieve()
                    .body(ReservedOrderResponse.class);

            if (response == null || !"0".equals(response.rtCd())) {
                log.error("KIS 예약주문 실패 - rtCd: {}, msgCd: {}, msg: {}",
                        response != null ? response.rtCd() : "null",
                        response != null ? response.msgCd() : "null",
                        response != null ? response.msg1() : "null");
                throw KisErrorMapper.toApiException(response != null ? response.msgCd() : null);
            }

            if (response.output() == null || response.output().rsvnOrdSeq() == null) {
                log.error("KIS 예약주문 성공 응답에 RSVN_ORD_SEQ 없음 - rtCd: {}, msg: {}",
                        response.rtCd(), response.msg1());
                throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR);
            }

            return new KisReservedOrderResult(response.output().rsvnOrdSeq());

        } catch (RestClientException e) {
            log.error("KIS 예약주문 API 호출 실패: {}", e.getMessage());
            throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR);
        }
    }

    // ── KIS API 결과 ────────────────────────────────────────────────────────

    /** 예약주문 접수 후 KIS 가 발급한 예약주문 순번 (RSVN_ORD_SEQ). orders.kis_rsvn_seq 저장용. */
    public record KisReservedOrderResult(String rsvnOrdSeq) {}

    // ── KIS API 응답 파싱용 내부 레코드 ────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ReservedOrderResponse(
            @JsonProperty("rt_cd")  String rtCd,
            @JsonProperty("msg_cd") String msgCd,
            @JsonProperty("msg1")   String msg1,
            @JsonProperty("output") ReservedOrderOutput output
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ReservedOrderOutput(
            @JsonProperty("RSVN_ORD_SEQ") String rsvnOrdSeq
    ) {}
}
