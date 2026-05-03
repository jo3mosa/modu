package com.modu.backend.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** 소셜 로그인 요청 DTO */
public record SocialLoginRequest(
        @NotBlank String code  // 프론트엔드가 리다이렉트 URL에서 추출한 인가 코드
) {}
