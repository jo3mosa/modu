package com.modu.backend.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "온보딩 완료 여부")
public record OnboardingStatus(
        @Schema(description = "투자 성향 설문 완료 여부 (investment_profiles 데이터 존재 여부)", example = "true")
        boolean isSurveyCompleted,

        @Schema(description = "매매 룰셋 설정 완료 여부 (trading_rules 데이터 존재 여부)", example = "false")
        boolean isRuleSetCompleted
) {}
