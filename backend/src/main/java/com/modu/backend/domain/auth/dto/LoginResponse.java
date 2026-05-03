package com.modu.backend.domain.auth.dto;

/**
 * 로그인/테스트 로그인 성공 시 응답 data 필드
 *
 * ApiResponse<LoginResponse> 형태로 반환
 */
public record LoginResponse(
        Long userId,
        String nickname,
        String email,       // 소셜 동의 미완료 시 null
        OnboardingStatus onboarding
) {}
