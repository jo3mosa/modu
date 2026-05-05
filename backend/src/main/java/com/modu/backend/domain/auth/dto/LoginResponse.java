package com.modu.backend.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "로그인 성공 응답")
public record LoginResponse(
        @Schema(description = "사용자 고유 ID", example = "1")
        Long userId,

        @Schema(description = "닉네임", example = "김모두")
        String nickname,

        @Schema(description = "이메일 (카카오 동의 시에만 존재)", example = "modu@kakao.com", nullable = true)
        String email,

        @Schema(description = "온보딩 완료 여부")
        OnboardingStatus onboarding
) {}
