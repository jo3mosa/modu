package com.modu.backend.domain.discovery.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 종목 핵심 지표 — S14P31B106-362
 *
 * dividendYield: 데이터 부재 — 항상 null. 추후 DA 적재 시 채움.
 */
@Schema(description = "종목 핵심 지표")
public record StockMetricsResponse(
        @Schema(description = "ATR ratio (%) — 변동성", example = "2.6")
        Double atr,

        @Schema(description = "ROE (%) — 자기자본이익률", example = "14.2")
        Double roe,

        @Schema(description = "PER — 주가수익비율", example = "14.8")
        Double per,

        @Schema(description = "배당수익률 (%) — 데이터 부재 시 null", example = "1.9", nullable = true)
        Double dividendYield
) {}
