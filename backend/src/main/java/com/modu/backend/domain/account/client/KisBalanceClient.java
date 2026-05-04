package com.modu.backend.domain.account.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.modu.backend.domain.account.dto.HoldingResponse;
import com.modu.backend.domain.account.dto.PortfolioResponse;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.error.CommonErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Collections;
import java.util.List;

/**
 * KIS 주식잔고조회 API 클라이언트
 *
 * GET /uapi/domestic-stock/v1/trading/inquire-balance
 * tr_id: TTTC8434R (실전투자 전용)
 *
 * [필드 매핑] KIS output1 → HoldingResponse
 * - pdno          → stockCode
 * - prdt_name     → stockName
 * - hldg_qty      → quantity
 * - pchs_avg_pric → avgBuyPrice
 * - prpr          → currentPrice
 * - evlu_pfls_amt → pnl
 * - evlu_pfls_rt  → pnlPct
 *
 * [주의] hldg_qty = 0인 종목은 당일 전량 매도된 잔고이므로 필터링
 */
@Slf4j
@Component
public class KisBalanceClient {

    private static final String BALANCE_PATH = "/uapi/domestic-stock/v1/trading/inquire-balance";
    private static final String TR_ID = "TTTC8434R";

    private final RestClient kisRestClient;

    public KisBalanceClient(RestClient kisRestClient) {
        this.kisRestClient = kisRestClient;
    }

    /**
     * 사용자 보유 주식 목록 조회
     *
     * @param accessToken KIS Bearer 액세스 토큰
     * @param appKey      복호화된 App Key
     * @param appSecret   복호화된 App Secret
     * @param cano        계좌번호 앞 8자리
     * @param acntPrdtCd  계좌 상품 코드
     */
    public PortfolioResponse getPortfolio(String accessToken, String appKey, String appSecret,
                                          String cano, String acntPrdtCd) {
        try {
            BalanceResponse response = kisRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(BALANCE_PATH)
                            .queryParam("CANO", cano)
                            .queryParam("ACNT_PRDT_CD", acntPrdtCd)
                            .queryParam("AFHR_FLPR_YN", "N")
                            .queryParam("OFL_YN", "")
                            .queryParam("INQR_DVSN", "01")
                            .queryParam("UNPR_DVSN", "01")
                            .queryParam("FUND_STTL_ICLD_YN", "N")
                            .queryParam("FNCG_AMT_AUTO_RDPT_YN", "N")
                            .queryParam("PRCS_DVSN", "00")
                            .queryParam("CTX_AREA_FK100", "")
                            .queryParam("CTX_AREA_NK100", "")
                            .build())
                    .header("content-type", "application/json; charset=utf-8")
                    .header("authorization", "Bearer " + accessToken)
                    .header("appkey", appKey)
                    .header("appsecret", appSecret)
                    .header("tr_id", TR_ID)
                    .header("custtype", "P")
                    .retrieve()
                    .body(BalanceResponse.class);

            if (response == null || !"0".equals(response.rtCd())) {
                log.error("KIS 잔고 조회 실패 - rtCd: {}, msg: {}",
                        response != null ? response.rtCd() : "null",
                        response != null ? response.msg1() : "null");
                throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR);
            }

            List<HoldingResponse> holdings = mapToHoldings(response.output1());
            return new PortfolioResponse(holdings);

        } catch (RestClientException e) {
            log.error("KIS 잔고 조회 API 호출 실패: {}", e.getMessage());
            throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR);
        }
    }

    private List<HoldingResponse> mapToHoldings(List<Output1> output1List) {
        if (output1List == null) return Collections.emptyList();

        return output1List.stream()
                .filter(item -> parseLong(item.hldgQty()) > 0)  // 보유수량 0 필터링 (당일 전량 매도)
                .map(item -> new HoldingResponse(
                        item.pdno(),
                        item.prdtName(),
                        parseLong(item.hldgQty()),
                        parseLong(item.pchsAvgPric()),
                        parseLong(item.prpr()),
                        parseLong(item.evluPflsAmt()),
                        parseDouble(item.evluPflsRt())
                ))
                .toList();
    }

    private long parseLong(String value) {
        if (value == null || value.isBlank()) return 0L;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private double parseDouble(String value) {
        if (value == null || value.isBlank()) return 0.0;
        try {
            return Math.round(Double.parseDouble(value.trim()) * 100.0) / 100.0;
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    // ── KIS API 응답 파싱용 내부 레코드 ────────────────────────────────────────

    private record BalanceResponse(
            @JsonProperty("rt_cd") String rtCd,
            @JsonProperty("msg_cd") String msgCd,
            @JsonProperty("msg1") String msg1,
            @JsonProperty("output1") List<Output1> output1
    ) {}

    private record Output1(
            @JsonProperty("pdno") String pdno,               // 종목코드
            @JsonProperty("prdt_name") String prdtName,      // 종목명
            @JsonProperty("hldg_qty") String hldgQty,        // 보유수량
            @JsonProperty("pchs_avg_pric") String pchsAvgPric, // 매입평균가격
            @JsonProperty("prpr") String prpr,               // 현재가
            @JsonProperty("evlu_pfls_amt") String evluPflsAmt, // 평가손익금액
            @JsonProperty("evlu_pfls_rt") String evluPflsRt   // 평가손익율
    ) {}
}
