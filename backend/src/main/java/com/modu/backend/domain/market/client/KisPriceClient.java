package com.modu.backend.domain.market.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.modu.backend.domain.market.dto.StockDetailResponse;
import com.modu.backend.domain.market.exception.MarketErrorCode;
import com.modu.backend.global.config.KisApiProperties;
import com.modu.backend.global.error.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * KIS 주식 현재가 시세 API 클라이언트 (v1, FHKST01010100)
 *
 * GET /uapi/domestic-stock/v1/quotations/inquire-price
 * - 현재가, 등락율, 거래량, 시/고/저가, 시가총액 포함
 * - 플랫폼 글로벌 자격증명 사용 (사용자 계좌 불필요)
 */
@Slf4j
@Component
public class KisPriceClient {

    private static final String PRICE_PATH = "/uapi/domestic-stock/v1/quotations/inquire-price";
    private static final String TR_ID = "FHKST01010100";

    private final RestClient kisRestClient;
    private final KisApiProperties kisApiProperties;

    public KisPriceClient(RestClient kisRestClient, KisApiProperties kisApiProperties) {
        this.kisRestClient = kisRestClient;
        this.kisApiProperties = kisApiProperties;
    }

    /**
     * 종목 현재가 시세 조회
     *
     * @param accessToken 플랫폼 KIS 액세스 토큰
     * @param stockCode   종목코드 (6자리)
     * @param stockName   종목명 (stock_master에서 조회한 값)
     * @param marketType  시장구분 (stock_master에서 조회한 값)
     */
    public StockDetailResponse getStockDetail(String accessToken, String stockCode,
                                               String stockName, String marketType) {
        try {
            PriceResponse response = kisRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(PRICE_PATH)
                            .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                            .queryParam("FID_INPUT_ISCD", stockCode)
                            .build())
                    .header("content-type", "application/json")
                    .header("authorization", "Bearer " + accessToken)
                    .header("appkey", kisApiProperties.getAppKey())
                    .header("appsecret", kisApiProperties.getAppSecret())
                    .header("tr_id", TR_ID)
                    .header("custtype", "P")
                    .retrieve()
                    .body(PriceResponse.class);

            if (response == null || !"0".equals(response.rtCd())) {
                log.error("KIS 시세 조회 실패 - stockCode: {}, rtCd: {}, msg: {}",
                        stockCode,
                        response != null ? response.rtCd() : "null",
                        response != null ? response.msg1() : "null");
                throw new ApiException(MarketErrorCode.KIS_PRICE_FETCH_FAILED);
            }

            return mapToResponse(stockCode, stockName, marketType, response.output());

        } catch (RestClientException e) {
            log.error("KIS 시세 조회 API 호출 실패 - stockCode: {}, error: {}", stockCode, e.getMessage());
            throw new ApiException(MarketErrorCode.KIS_PRICE_FETCH_FAILED);
        }
    }

    private StockDetailResponse mapToResponse(String stockCode, String stockName,
                                               String marketType, Output output) {
        return new StockDetailResponse(
                stockCode,
                stockName,
                marketType,
                parseLong(output.stckPrpr()),
                parseDouble(output.prdyCtrt()),
                output.prdyVrssSign(),
                parseLong(output.acmlVol()),
                parseLong(output.htsAvls()),
                parseLong(output.stckOprc()),
                parseLong(output.stckHgpr()),
                parseLong(output.stckLwpr())
        );
    }

    private long parseLong(String value) {
        if (value == null || value.isBlank()) return 0L;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.error("KIS 시세 응답 Long 파싱 실패 - value: '{}'", value);
            return 0L;
        }
    }

    private double parseDouble(String value) {
        if (value == null || value.isBlank()) return 0.0;
        try {
            return Math.round(Double.parseDouble(value.trim()) * 100.0) / 100.0;
        } catch (NumberFormatException e) {
            log.error("KIS 시세 응답 Double 파싱 실패 - value: '{}'", value);
            return 0.0;
        }
    }

    // ── KIS API 응답 파싱용 내부 레코드 ────────────────────────────────────────

    private record PriceResponse(
            @JsonProperty("rt_cd") String rtCd,
            @JsonProperty("msg_cd") String msgCd,
            @JsonProperty("msg1") String msg1,
            @JsonProperty("output") Output output
    ) {}

    private record Output(
            @JsonProperty("stck_prpr") String stckPrpr,       // 현재가
            @JsonProperty("prdy_vrss_sign") String prdyVrssSign, // 전일 대비 부호
            @JsonProperty("prdy_ctrt") String prdyCtrt,       // 전일 대비율
            @JsonProperty("acml_vol") String acmlVol,          // 누적 거래량
            @JsonProperty("hts_avls") String htsAvls,          // 시가총액
            @JsonProperty("stck_oprc") String stckOprc,        // 시가
            @JsonProperty("stck_hgpr") String stckHgpr,        // 고가
            @JsonProperty("stck_lwpr") String stckLwpr         // 저가
    ) {}
}
