package com.modu.backend.domain.market.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "캔들 데이터 목록 응답")
public record CandleListResponse(
        @Schema(description = "종목코드", example = "005930")
        String stockCode,

        @Schema(description = "요청한 기간 타입 (D/W/M/1/5/60)", example = "D")
        String period,

        @Schema(description = "캔들 데이터 목록 (과거순 정렬)")
        List<CandleResponse> candles
) {}
