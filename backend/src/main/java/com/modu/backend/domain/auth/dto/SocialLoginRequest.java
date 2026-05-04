package com.modu.backend.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "소셜 로그인 요청")
public record SocialLoginRequest(
        @Schema(description = "프론트엔드가 카카오 리다이렉트 URL에서 추출한 인가 코드", example = "A1b2C3d4E5f6...")
        @NotBlank String code
) {}
