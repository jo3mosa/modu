package com.modu.backend.domain.market.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "종목 상세 정보 응답")
public record StockDetailResponse(
        @Schema(description = "종목코드", example = "005930")
        String stockCode,

        @Schema(description = "종목명", example = "삼성전자")
        String stockName,

        @Schema(description = "시장 구분 (KOSPI / KOSDAQ)", example = "KOSPI")
        String marketType,

        @Schema(description = "현재가 (원)", example = "82000")
        Long currentPrice,

        @Schema(description = "전일 대비율 (%)", example = "1.23")
        Double compareRate,

        @Schema(description = "전일 대비 부호 (1,2:상승 / 3:보합 / 4,5:하락)", example = "2")
        String compareSign,

        @Schema(description = "누적 거래량", example = "15000000")
        Long accumulatedVolume,

        @Schema(description = "시가총액 (원)", example = "489000000000000")
        Long marketCap,

        @Schema(description = "당일 시가 (원)", example = "81500")
        Long openPrice,

        @Schema(description = "당일 고가 (원)", example = "83000")
        Long highPrice,

        @Schema(description = "당일 저가 (원)", example = "81000")
        Long lowPrice
) {}
