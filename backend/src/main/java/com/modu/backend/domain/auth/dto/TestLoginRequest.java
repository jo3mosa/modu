package com.modu.backend.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "개발용 테스트 로그인 요청")
public record TestLoginRequest(
        @Schema(description = "로그인할 사용자 ID (DB에 존재해야 함)", example = "1")
        @NotNull Long userId
) {}
