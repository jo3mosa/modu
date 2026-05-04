package com.modu.backend.domain.account.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.modu.backend.domain.account.dto.AccountSummaryResponse;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.error.CommonErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * KIS 투자계좌자산현황조회 API 클라이언트
 *
 * GET /uapi/domestic-stock/v1/trading/inquire-account-balance
 * tr_id: CTRP6548R (실전투자 전용)
 *
 * [필드 매핑] KIS Output2 → AccountSummaryResponse
 * - tot_asst_amt      → totalAsset
 * - dncl_amt          → availableCash (D+2 예수금)
 * - evlu_amt_smtl     → totalEvalAmount
 * - pchs_amt_smtl     → totalBuyAmount
 * - evlu_pfls_amt_smtl → totalPnl
 * - 수익률             → 서버 계산: totalPnl / totalBuyAmount * 100
 */
@Slf4j
@Component
public class KisAssetClient {

    private static final String ASSET_PATH = "/uapi/domestic-stock/v1/trading/inquire-account-balance";
    private static final String TR_ID = "CTRP6548R";

    private final RestClient kisRestClient;

    public KisAssetClient(RestClient kisRestClient) {
        this.kisRestClient = kisRestClient;
    }

    /**
     * 사용자 계좌 자산 현황 조회
     *
     * @param accessToken      KIS Bearer 액세스 토큰
     * @param appKey           복호화된 App Key
     * @param appSecret        복호화된 App Secret
     * @param cano             계좌번호 앞 8자리
     * @param acntPrdtCd       계좌 상품 코드
     */
    public AccountSummaryResponse getAssetSummary(String accessToken, String appKey, String appSecret,
                                                   String cano, String acntPrdtCd) {
        try {
            AssetResponse response = kisRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(ASSET_PATH)
                            .queryParam("CANO", cano)
                            .queryParam("ACNT_PRDT_CD", acntPrdtCd)
                            .queryParam("INQR_DVSN_1", "")
                            .queryParam("BSPR_BF_DT_APLY_YN", "")
                            .build())
                    .header("content-type", "application/json; charset=utf-8")
                    .header("authorization", "Bearer " + accessToken)
                    .header("appkey", appKey)
                    .header("appsecret", appSecret)
                    .header("tr_id", TR_ID)
                    .header("custtype", "P")
                    .retrieve()
                    .body(AssetResponse.class);

            if (response == null || !"0".equals(response.rtCd())) {
                log.error("KIS 자산 조회 실패 - rtCd: {}, msg: {}",
                        response != null ? response.rtCd() : "null",
                        response != null ? response.msg1() : "null");
                throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR);
            }

            return mapToResponse(response.output2());

        } catch (RestClientException e) {
            log.error("KIS 자산 조회 API 호출 실패: {}", e.getMessage());
            throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR);
        }
    }

    private AccountSummaryResponse mapToResponse(Output2 output2) {
        long totalBuyAmount = parseLong(output2.pchsAmtSmtl());
        long totalPnl = parseLong(output2.evluPflsAmtSmtl());

        // 수익률: 매입금액이 0이면 0.0으로 처리 (DivisionByZero 방지)
        double totalPnlPct = totalBuyAmount != 0
                ? Math.round((double) totalPnl / totalBuyAmount * 10000.0) / 100.0
                : 0.0;

        return new AccountSummaryResponse(
                parseLong(output2.totAsstAmt()),
                parseLong(output2.dnclAmt()),
                parseLong(output2.evluAmtSmtl()),
                totalBuyAmount,
                totalPnl,
                totalPnlPct
        );
    }

    private long parseLong(String value) {
        if (value == null || value.isBlank()) return 0L;
        return Long.parseLong(value.trim());
    }

    // ── KIS API 응답 파싱용 내부 레코드 ────────────────────────────────────────

    private record AssetResponse(
            @JsonProperty("rt_cd") String rtCd,
            @JsonProperty("msg_cd") String msgCd,
            @JsonProperty("msg1") String msg1,
            @JsonProperty("Output2") Output2 output2
    ) {}

    private record Output2(
            @JsonProperty("pchs_amt_smtl") String pchsAmtSmtl,       // 매입금액합계
            @JsonProperty("evlu_pfls_amt_smtl") String evluPflsAmtSmtl, // 평가손익금액합계
            @JsonProperty("evlu_amt_smtl") String evluAmtSmtl,        // 평가금액합계
            @JsonProperty("tot_asst_amt") String totAsstAmt,           // 총자산금액
            @JsonProperty("dncl_amt") String dnclAmt                   // 예수금액 (D+2 주문가능현금)
    ) {}
}
