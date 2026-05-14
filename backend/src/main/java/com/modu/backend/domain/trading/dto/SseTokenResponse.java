package com.modu.backend.domain.trading.dto;

/**
 * SSE 단기 토큰 발급 응답 DTO
 *
 * GET /api/v1/orders/connect 호출 시 query param 으로 사용되는 단기 JWT 와 유효 시간을 함께 제공.
 */
public record SseTokenResponse(
        String token,
        long   expiresIn   // 유효 시간 (초)
) {}
