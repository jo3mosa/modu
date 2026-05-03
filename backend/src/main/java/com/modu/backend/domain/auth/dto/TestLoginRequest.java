package com.modu.backend.domain.auth.dto;

import jakarta.validation.constraints.NotNull;

/** 개발용 테스트 로그인 요청 DTO */
public record TestLoginRequest(
        @NotNull Long userId
) {}
