package com.modu.backend.domain.strategy.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record RuleUpdateRequest(
        @NotNull @Min(1) Integer stopLossRate,
        @NotNull @Min(1) Integer takeProfitRate,
        @NotNull @Min(1) Long maxDailyOrderCount,
        @NotNull @Min(1) Long maxDailyLossAmount
) {
}
