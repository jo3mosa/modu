package com.modu.backend.domain.strategy.dto;

import java.time.OffsetDateTime;

public record RuleUpdateResponse(
        Integer stopLossRate,
        Integer takeProfitRate,
        Long maxDailyOrderCount,
        Long maxDailyLossAmount,
        Long aiBudgetAmount,
        OffsetDateTime updatedAt,
        Long version
) {
}
