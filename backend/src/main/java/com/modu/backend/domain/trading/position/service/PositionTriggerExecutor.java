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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 트리거 발동 실행자 — 단일 트랜잭션 안에서 Order INSERT + PositionThreshold 비활성화,
 * Kafka 발행은 commit 후 (afterCommit) 실행
 *
 * [트랜잭션 경계]
 *  메서드 전체 @Transactional. OrderService.doPlaceOrder 와 동일 패턴.
 *  - Order INSERT + markTriggered 가 같은 tx 에서 commit
 *  - Kafka 발행은 afterCommit 콜백 — commit 완료 후 실행돼 Consumer race 차단
 *    (commit 전 발행하면 Consumer 가 uncommitted row 못 찾고 메시지 drop → 트리거 유실)
 *
 * [트레이드오프]
 *  - publish 실패 시: Order=PENDING orphan + position is_active=FALSE → 자동 재시도 없음
 *  - sweeper 로 PENDING orphan 정리 + 필요 시 position 재활성화 (별도 이슈)
 *
 * [멱등성]
 *  - Order.idempotencyKey = UUID — 매 트리거마다 새 UUID. position_thresholds 의 partial unique index 가
 *    같은 (user, stock) 활성 row 1개 보장 → 트리거 자체가 1회성
 *  - markTriggered 로 is_active=FALSE → 다음 사이클 폴링 대상에서 제외
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
        // commit 후 발행 — Consumer race 차단 (클래스 주석 [트랜잭션 경계] 참조)
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                tradeOrderProducer.publishOrderSubmitted(message);
                log.info("Position 트리거 Kafka 발행 완료(afterCommit) - userId: {}, stockCode: {}, orderId: {}",
                        position.getUserId(), position.getStockCode(), order.getId());
            }
        });

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
