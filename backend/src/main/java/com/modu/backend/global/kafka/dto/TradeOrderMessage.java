package com.modu.backend.global.kafka.dto;

import com.modu.backend.domain.trading.entity.OrderSide;
import com.modu.backend.domain.trading.entity.OrderSource;
import com.modu.backend.domain.trading.entity.OrderType;

import java.time.OffsetDateTime;

/**
 * trade.order.submitted 토픽 메시지 DTO
 *
 * 수동 / AI 자동 / 손절익절 트리거 주문의 통합 진입점.
 * 발행자: OrderService(수동), PositionMonitor(302), SignalHandlerService(263)
 * 소비자: KisOrderConsumer(306)
 *
 * [필드 매핑]
 * - orderId       → orders.idempotency_key (UUID, 중복 주문 방지 키)
 * - parentOrderId → orders.parent_order_id (손절/익절 시 원주문 ID, 그 외 null)
 * - source        → orders.source (OrderSource enum 의 name() 문자열)
 * - ruleHistoryId → orders.rule_history_id (참조한 매매 규칙 버전)
 *
 * [타입 정책]
 * Kafka 메시지는 외부 시스템과의 계약이라 enum 직접 사용 대신 String 으로 직렬화한다.
 * 역직렬화 시 unknown enum 값으로 깨지는 사고를 방지하기 위함.
 * 발행 측은 of(...) 팩토리로 enum.name() 변환을 거치며, 소비 측은 String 그대로 다루거나
 * 필요 시 valueOf() 로 변환.
 */
public record TradeOrderMessage(
        String         orderId,
        Long           parentOrderId,
        Long           userId,
        String         stockCode,
        String         side,
        String         orderType,
        Long           quantity,
        Long           limitPrice,
        String         source,
        Long           ruleHistoryId,
        OffsetDateTime submittedAt
) {

    /**
     * 도메인 enum 을 안전하게 문자열로 변환하여 메시지 생성
     */
    public static TradeOrderMessage of(
            String orderId,
            Long parentOrderId,
            Long userId,
            String stockCode,
            OrderSide side,
            OrderType orderType,
            Long quantity,
            Long limitPrice,
            OrderSource source,
            Long ruleHistoryId,
            OffsetDateTime submittedAt
    ) {
        return new TradeOrderMessage(
                orderId,
                parentOrderId,
                userId,
                stockCode,
                side.name(),
                orderType.name(),
                quantity,
                limitPrice,
                source.name(),
                ruleHistoryId,
                submittedAt
        );
    }
}
