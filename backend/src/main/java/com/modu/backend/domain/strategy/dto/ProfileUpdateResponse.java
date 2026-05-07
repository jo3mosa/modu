package com.modu.backend.domain.strategy.dto;

import java.time.OffsetDateTime;

public record ProfileUpdateResponse(
        InvestmentRiskLevel riskLevel,
        long riskScore,
        String profileSummary,
        OffsetDateTime createdAt
) {
}
