package com.modu.backend.global.kafka.dto;

import com.modu.backend.domain.trading.entity.OrderSide;

import java.time.OffsetDateTime;

/**
 * trade.order.executed 토픽 메시지 DTO
 *
 * KIS WebSocket 체결 통보(H0STCNI0) 처리부에서 발행, PortfolioUpdateConsumer(291) 가 소비.
 * 단건 체결 단위로 발행되며, isFinalFill 로 전량 체결 여부를 구분한다.
 *
 * [필드 매핑]
 * - orderId             → orders.id (우리 시스템 주문 ID)
 * - kisOrderNo          → orders.kis_order_no
 * - executedQuantity    → 이번 체결 수량 (단건)
 * - executedPrice       → 이번 체결 단가
 * - totalFilledQuantity → 누적 체결 수량 (orders.filled_quantity 갱신 기준값)
 * - isFinalFill         → 전량 체결 여부 (true 시 status FILLED 전이 + trade_pnl_records INSERT)
 *
 * [타입 정책]
 * TradeOrderMessage 와 동일하게 side 는 String 으로 직렬화 (외부 계약 안정성).
 */
public record TradeOrderExecutedMessage(
        Long           orderId,
        String         kisOrderNo,
        Long           userId,
        String         stockCode,
        String         side,
        Long           executedQuantity,
        Long           executedPrice,
        Long           totalFilledQuantity,
        boolean        isFinalFill,
        OffsetDateTime executedAt
) {

    /**
     * 도메인 enum 을 안전하게 문자열로 변환하여 메시지 생성
     */
    public static TradeOrderExecutedMessage of(
            Long orderId,
            String kisOrderNo,
            Long userId,
            String stockCode,
            OrderSide side,
            Long executedQuantity,
            Long executedPrice,
            Long totalFilledQuantity,
            boolean isFinalFill,
            OffsetDateTime executedAt
    ) {
        return new TradeOrderExecutedMessage(
                orderId,
                kisOrderNo,
                userId,
                stockCode,
                side.name(),
                executedQuantity,
                executedPrice,
                totalFilledQuantity,
                isFinalFill,
                executedAt
        );
    }
}
