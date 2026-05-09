package com.modu.backend.domain.strategy.dto;

import java.time.OffsetDateTime;

public record RuleResponse(
        Integer stopLossRate,
        Integer takeProfitRate,
        Long maxDailyOrderCount,
        Long maxDailyLossAmount,
        OffsetDateTime updatedAt,
        Long version
) {
}
