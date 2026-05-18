package com.modu.backend.domain.trading.execution.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.error.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * KIS 주식 예약주문조회 API 클라이언트 — S14P31B106-291
 *
 * GET /uapi/domestic-stock/v1/trading/order-resv-ccnl  TR_ID=CTSC0004R (실전 전용)
 *
 * [용도]
 *  매일 09:05 KST 변환 모니터링 스케줄러가 호출. 전일 발행한 예약주문이 정규장 개시 시 일반 주문으로
 *  변환된 결과를 받아 우리 DB 의 orders.kis_order_no 동기화 + 변환 거부 시 order.reject.
 *
 * [응답 핵심 필드]
 *  rsvn_ord_seq  예약주문 순번 (= orders.kis_rsvn_seq 매칭 키)
 *  odno          변환된 새 ODER_NO (= orders.kis_order_no 업데이트 대상)
 *  prcs_rslt     처리결과 ("처리완료" / "거부" / "미처리" 등)
 *  rjct_rson2    거부 사유 (있으면 거부)
 *
 * [Content-Type]
 *  KIS 명세 권장값 application/json; charset=utf-8.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KisReservedOrderInquiryClient {

    private static final String PATH = "/uapi/domestic-stock/v1/trading/order-resv-ccnl";
    private static final String TR_ID = "CTSC0004R";

    private final RestClient kisRestClient;

    public List<ReservedOrderInquiryResult> list(
            String accessToken, String appKey, String appSecret,
            String cano, String acntPrdtCd,
            String fromDate, String toDate) {

        try {
            InquiryResponse response = kisRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(PATH)
                            .queryParam("RSVN_ORD_ORD_DT", fromDate)
                            .queryParam("RSVN_ORD_END_DT", toDate)
                            .queryParam("RSVN_ORD_SEQ", "")
                            .queryParam("TMNL_MDIA_KIND_CD", "00")
                            .queryParam("CANO", cano)
                            .queryParam("ACNT_PRDT_CD", acntPrdtCd)
                            .queryParam("PRCS_DVSN_CD", "0")
                            .queryParam("CNCL_YN", "Y")
                            .queryParam("PDNO", "")
                            .queryParam("SLL_BUY_DVSN_CD", "")
                            .queryParam("CTX_AREA_FK200", "")
                            .queryParam("CTX_AREA_NK200", "")
                            .build())
                    .header("content-type", "application/json; charset=utf-8")
                    .header("authorization", "Bearer " + accessToken)
                    .header("appkey", appKey)
                    .header("appsecret", appSecret)
                    .header("tr_id", TR_ID)
                    .header("custtype", "P")
                    .retrieve()
                    .body(InquiryResponse.class);

            if (response == null || !"0".equals(response.rtCd())) {
                log.error("KIS 예약주문 조회 실패 - rtCd: {}, msgCd: {}, msg: {}",
                        response != null ? response.rtCd() : "null",
                        response != null ? response.msgCd() : "null",
                        response != null ? response.msg1() : "null");
                throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR);
            }
            return response.output() == null ? List.of() : response.output();
        } catch (RestClientException e) {
            log.error("KIS 예약주문 조회 API 호출 실패", e);
            throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR, e);
        }
    }

    // ── 응답 DTO ─────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record InquiryResponse(
            @JsonProperty("rt_cd")  String rtCd,
            @JsonProperty("msg_cd") String msgCd,
            @JsonProperty("msg1")   String msg1,
            @JsonProperty("output") List<ReservedOrderInquiryResult> output
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReservedOrderInquiryResult(
            @JsonProperty("rsvn_ord_seq") String rsvnOrdSeq,
            @JsonProperty("odno")         String odno,
            @JsonProperty("prcs_rslt")    String prcsRslt,
            @JsonProperty("rjct_rson2")   String rjctRson2,
            @JsonProperty("pdno")         String pdno
    ) {}
}
