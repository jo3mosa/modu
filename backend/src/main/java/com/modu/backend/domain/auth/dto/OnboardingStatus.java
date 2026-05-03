package com.modu.backend.domain.auth.dto;

/**
 * 로그인 응답의 onboarding 중첩 객체
 *
 * isSurveyCompleted : investment_profiles 데이터 존재 여부
 * isRuleSetCompleted: trading_rules 데이터 존재 여부
 */
public record OnboardingStatus(
        boolean isSurveyCompleted,
        boolean isRuleSetCompleted
) {}
