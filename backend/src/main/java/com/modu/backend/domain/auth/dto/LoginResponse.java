package com.modu.backend.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "로그인 응답")
public record LoginResponse(
        @Schema(description = "Authorization Bearer 헤더로 전송할 Access Token", example = "eyJhbGciOiJIUzI1NiJ9...")
        String accessToken,

        @Schema(description = "사용자 ID", example = "1")
        Long userId,

        @Schema(description = "닉네임", example = "모두")
        String nickname,

        @Schema(description = "이메일", example = "modu@kakao.com", nullable = true)
        String email,

        @Schema(description = "온보딩 완료 상태")
        OnboardingStatus onboarding
) {}
