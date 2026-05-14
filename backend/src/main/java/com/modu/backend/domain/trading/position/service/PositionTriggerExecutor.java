package com.modu.backend.domain.trading.position.service;

import com.modu.backend.domain.trading.entity.Order;
import com.modu.backend.domain.trading.entity.OrderSide;
import com.modu.backend.domain.trading.entity.OrderSource;
import com.modu.backend.domain.trading.entity.OrderStatus;
import com.modu.backend.domain.trading.entity.OrderType;
import com.modu.backend.domain.trading.kafka.producer.TradeOrderProducer;
import com.modu.backend.domain.trading.position.entity.PositionThreshold;
import com.modu.backend.domain.trading.position.entity.PositionTriggerReason;
import com.modu.backend.domain.trading.position.repository.PositionThresholdRepository;
import com.modu.backend.domain.trading.repository.OrderRepository;
import com.modu.backend.global.kafka.dto.TradeOrderMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 트리거 발동 실행자 — 단일 트랜잭션 안에서 Order INSERT + Kafka 발행 + PositionThreshold 비활성화
 *
 * [트랜잭션 경계]
 *  메서드 전체 @Transactional. OrderService.doPlaceOrder 와 동일 패턴.
 *  - Kafka 발행 실패 → ApiException 전파 → tx 롤백 → Order INSERT/markTriggered 둘 다 무효 → 다음 폴링 사이클 재시도
 *  - tx commit 후 Kafka 발행이 이미 끝난 시점이므로 발행 후 DB 실패 시나리오는 발생 X
 *
 * [멱등성]
 *  - Order.idempotencyKey = UUID — 매 트리거마다 새 UUID. position_thresholds 의 partial unique index 가
 *    같은 (user, stock) 활성 row 1개 보장 → 트리거 자체가 1회성
 *  - 발행 직후 markTriggered 로 is_active=FALSE → 다음 사이클 폴링 대상에서 제외
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PositionTriggerExecutor {

    private final OrderRepository orderRepository;
    private final PositionThresholdRepository positionThresholdRepository;
    private final TradeOrderProducer tradeOrderProducer;

    @Transactional
    public void execute(Long positionThresholdId, PositionTriggerReason reason) {
        PositionThreshold position = positionThresholdRepository.findById(positionThresholdId)
                .orElse(null);
        if (position == null || !position.isActive()) {
            // 다른 사이클이 이미 처리했거나 외부 비활성화 — 정상 종료
            return;
        }

        OrderSource source = toOrderSource(reason);
        String idempotencyKey = UUID.randomUUID().toString();

        Order order = Order.builder()
                .userId(position.getUserId())
                .stockCode(position.getStockCode())
                .side(OrderSide.SELL)
                .orderType(OrderType.MARKET)
                .quantity(position.getQuantity())
                .limitPrice(null)
                .status(OrderStatus.PENDING)
                .source(source)
                .idempotencyKey(idempotencyKey)
                .build();
        orderRepository.saveAndFlush(order);

        TradeOrderMessage message = TradeOrderMessage.of(
                idempotencyKey,
                null,
                position.getUserId(),
                position.getStockCode(),
                OrderSide.SELL,
                OrderType.MARKET,
                position.getQuantity(),
                null,
                source,
                null,
                OffsetDateTime.now()
        );
        tradeOrderProducer.publishOrderSubmitted(message);

        position.markTriggered(reason, order.getId());

        log.info("Position 트리거 발동 - userId: {}, stockCode: {}, reason: {}, orderId: {}, quantity: {}",
                position.getUserId(), position.getStockCode(), reason, order.getId(), position.getQuantity());
    }

    private OrderSource toOrderSource(PositionTriggerReason reason) {
        return switch (reason) {
            case USER_STOP_LOSS, AI_STOP_LOSS -> OrderSource.STOP_LOSS;
            case USER_TAKE_PROFIT, AI_TAKE_PROFIT -> OrderSource.TAKE_PROFIT;
        };
    }
}
