package com.modu.backend.global.kafka.dto;

import java.time.OffsetDateTime;

/**
 * trade.order.submitted 토픽 메시지 DTO
 * - AI 자동 / 손절익절 / 수동 주문의 통합 진입점
 * - orderId는 orders.idempotency_key에 저장되어 중복 주문 방지에 사용
 */
public record TradeOrderMessage(
    String         orderId,        // 주문 추적 ID (UUID) → orders.idempotency_key
    Long           parentOrderId,  // 원주문 ID (손절/익절 시만) → orders.parent_order_id
    Long           userId,         // 사용자 ID → orders.user_id
    String         stockCode,      // 종목 코드 → orders.stock_code
    String         side,           // BUY or SELL → orders.side
    String         orderType,      // MARKET or LIMIT → orders.order_type
    Long           quantity,       // 주문 수량 → orders.quantity
    Long           limitPrice,     // 지정가 (null이면 시장가) → orders.limit_price
    String         source,         // AI_DECISION, STOP_LOSS, TAKE_PROFIT, MANUAL → orders.source
    Long           ruleHistoryId,  // 참조한 매매 규칙 버전 → orders.rule_history_id
    OffsetDateTime submittedAt     // 주문 요청 시각 → orders.submitted_at
) {}
