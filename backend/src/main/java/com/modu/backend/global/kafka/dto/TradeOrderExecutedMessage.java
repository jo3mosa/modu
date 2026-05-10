package com.modu.backend.global.kafka.dto;

import java.time.OffsetDateTime;

/**
 * trade.order.executed 토픽 메시지 DTO
 * - KIS WebSocket 체결 통보 수신 후 발행
 * - orderId는 orders.id (BIGSERIAL) — Consumer가 DB 조회 없이 바로 UPDATE 가능
 */
public record TradeOrderExecutedMessage(
    Long           orderId,          // DB 주문 ID → orders.id
    Long           userId,           // 사용자 ID → order_executions.user_id
    String         stockCode,        // 종목 코드 (하위 처리 전달용)
    String         side,             // BUY or SELL (PNL 계산 분기용)
    String         kisOrderNo,       // KIS 주문번호 → orders.kis_order_no
    String         kisExecutionNo,   // KIS 체결번호 → order_executions.kis_execution_no
    Long           executedQuantity, // 체결 수량 → order_executions.executed_quantity
    Long           executedPrice,    // 체결가 → order_executions.executed_price
    Long           executedAmount,   // 체결 금액 → order_executions.executed_amount
    Long           commission,       // 수수료 → orders.commission
    Long           tax,              // 세금 → orders.tax
    Boolean        isFinalFill,      // 전량 체결 여부 (FILLED vs PARTIAL_FILLED 분기)
    OffsetDateTime executedAt        // 체결 시각 → order_executions.executed_at
) {}
