package com.modu.backend.domain.strategy.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record ProfileResponse(
        InvestmentRiskLevel riskLevel,
        String profileSummary,
        List<ProfileAnswerResponse> answers,
        String freeText,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        Long version
) {
}
