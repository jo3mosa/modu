package com.modu.backend.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "토큰 응답")
public record TokenResponse(
        @Schema(description = "Authorization Bearer 헤더로 전송할 Access Token", example = "eyJhbGciOiJIUzI1NiJ9...")
        String accessToken
) {}
