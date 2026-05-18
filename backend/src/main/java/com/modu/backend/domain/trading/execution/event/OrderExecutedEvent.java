package com.modu.backend.domain.trading.execution.event;

import com.modu.backend.domain.trading.entity.OrderSide;

/**
 * 체결 반영 완료 도메인 이벤트 — S14P31B106-291
 *
 * PortfolioUpdateConsumer 가 DB 갱신 (markFilled + INSERT) 후 publish.
 * OrderExecutedRedisSyncListener 가 AFTER_COMMIT 으로 받아 Redis 3종 키 갱신.
 *
 * 같은 트랜잭션 안에서 publish → DB rollback 시 listener 도 발화 안 함 (정합성 보장).
 */
public record OrderExecutedEvent(
        Long orderId,
        Long userId,
        String stockCode,
        OrderSide side,
        boolean isFinalFill
) {}
