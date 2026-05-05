package com.modu.backend.domain.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "사용자 자산 현황 응답")
public record AccountSummaryResponse(
        @Schema(description = "총 자산 (예수금 + 총 평가금액)", example = "15000000")
        Long totalAsset,

        @Schema(description = "주문 가능 현금 (D+2 예수금)", example = "5000000")
        Long availableCash,

        @Schema(description = "주식 총 평가금액", example = "10000000")
        Long totalEvalAmount,

        @Schema(description = "주식 총 매입금액", example = "9000000")
        Long totalBuyAmount,

        @Schema(description = "총 평가손익 금액", example = "1000000")
        Long totalPnl,

        @Schema(description = "총 수익률 (%)", example = "11.11")
        Double totalPnlPct
) {}
