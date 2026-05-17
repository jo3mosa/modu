package com.modu.backend.domain.strategy.dto;

import com.modu.backend.domain.strategy.entity.AutoTradeSettings;
import com.modu.backend.domain.strategy.entity.AutoTradeStatus;

import java.time.OffsetDateTime;

/**
 * PATCH /api/v1/strategies/me/status 응답 (S14P31B106-292)
 *
 * isActive 매핑:
 *  ACTIVE         → true
 *  INACTIVE       → false
 *  KILL_SWITCHED  → false (외부 노출은 OFF 와 동일. 503 응답일 때만 발동 사실 노출)
 */
public record AutoTradeStatusResponse(
        boolean isActive,
        OffsetDateTime updatedAt
) {
    public static AutoTradeStatusResponse from(AutoTradeSettings settings) {
        return new AutoTradeStatusResponse(
                settings.getAutoTradeStatus() == AutoTradeStatus.ACTIVE,
                settings.getUpdatedAt()
        );
    }
}
