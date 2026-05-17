package com.modu.backend.domain.strategy.dto;

import jakarta.validation.constraints.NotNull;

/**
 * PATCH /api/v1/strategies/me/status 요청 (S14P31B106-292)
 *
 * isActive=true  → ACTIVE 전환 (KILL_SWITCHED 도 해제)
 * isActive=false → INACTIVE 전환 (KILL_SWITCHED 인 경우 kill switch 기록 클리어)
 */
public record AutoTradeStatusRequest(
        @NotNull(message = "isActive 는 필수입니다.")
        Boolean isActive
) {}
