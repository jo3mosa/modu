package com.modu.backend.domain.market.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "캔들 데이터")
public record CandleResponse(
        @Schema(description = "일자(YYYYMMDD) 또는 시간(HHmmss)", example = "20250426")
        String timestamp,

        @Schema(description = "시가 (원)", example = "81500")
        Long openPrice,

        @Schema(description = "고가 (원)", example = "83000")
        Long highPrice,

        @Schema(description = "저가 (원)", example = "80500")
        Long lowPrice,

        @Schema(description = "종가 (원)", example = "82000")
        Long closePrice,

        @Schema(description = "거래량", example = "15000000")
        Long volume
) {}
