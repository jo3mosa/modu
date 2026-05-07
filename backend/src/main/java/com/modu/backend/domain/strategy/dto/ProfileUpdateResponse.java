package com.modu.backend.domain.strategy.dto;

import com.modu.backend.domain.auth.dto.OnboardingStatus;

import java.time.OffsetDateTime;

public record ProfileUpdateResponse(
        InvestmentRiskLevel riskLevel,
        long riskScore,
        String profileSummary,
        OffsetDateTime createdAt,
        OnboardingStatus onboarding
) {
}
