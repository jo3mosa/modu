package com.modu.backend.domain.trading.kafka.consumer;

import com.modu.backend.domain.trading.entity.Order;
import com.modu.backend.domain.trading.entity.OrderExecution;
import com.modu.backend.domain.trading.entity.OrderSide;
import com.modu.backend.domain.trading.entity.OrderStatus;
import com.modu.backend.domain.trading.entity.TradePnlRecord;
import com.modu.backend.domain.trading.execution.event.OrderExecutedEvent;
import com.modu.backend.domain.trading.repository.OrderExecutionRepository;
import com.modu.backend.domain.trading.repository.OrderRepository;
import com.modu.backend.domain.trading.repository.TradePnlRecordRepository;
import com.modu.backend.domain.trading.sse.OrderSseEmitterManager;
import com.modu.backend.domain.trading.sse.OrderSseEvent;
import com.modu.backend.global.kafka.constant.KafkaConsumerGroup;
import com.modu.backend.global.kafka.constant.KafkaTopic;
import com.modu.backend.global.kafka.dto.TradeOrderExecutedMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * trade.order.executed 토픽 소비자 — S14P31B106-291
 *
 * [책임]
 *  1. 멱등성 보장 — 동일 (order_id, kis_execution_no) 중복 메시지 silent skip
 *  2. orders 갱신 — markFilled (가중평균 + isFinalFill 전이) — Pessimistic Lock 으로 race 차단
 *  3. order_executions INSERT
 *  4. SELL 전량 체결 시 trade_pnl_records INSERT — 가장 최근 FILLED 매수 주문과 매칭
 *  5. SSE ORDER_EXECUTED 발송
 *  (Redis 갱신 / trade.settled 발행은 A-8 / A-9 에서 본 클래스 호출 또는 후속 listener 로 분리)
 *
 * [동시성]
 *  concurrency = 1 (KafkaConsumerConfig) — 직렬 처리. 동일 파티션 (userId) 내 메시지 순서 보장.
 *  Pessimistic Lock 으로 외부 (Sweeper / 사용자 수동 취소 등) 와의 race 도 차단.
 *
 * [ack 정책]
 *  성공 / 멱등 skip / 비즈니스 무시 (order 미매칭 등) 모두 ack.
 *  시스템 예외 (DB transport 실패 등) 만 미커밋 → Kafka 재시도.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortfolioUpdateConsumer {

    private final OrderRepository orderRepository;
    private final OrderExecutionRepository orderExecutionRepository;
    private final TradePnlRecordRepository tradePnlRecordRepository;
    private final OrderSseEmitterManager sseEmitterManager;
    private final ApplicationEventPublisher eventPublisher;

    @KafkaListener(
            topics = KafkaTopic.TRADE_ORDER_EXECUTED,
            groupId = KafkaConsumerGroup.PORTFOLIO_UPDATE
    )
    public void onMessage(TradeOrderExecutedMessage message, Acknowledgment ack) {
        try {
            processMessage(message);
        } catch (Exception e) {
            // 시스템 예외 — Kafka 재시도. ack 미커밋
            log.error("[PortfolioUpdateConsumer] 미처리 예외 - orderId: {}", message.orderId(), e);
            throw e;
        }
        ack.acknowledge();
    }

    @Transactional
    protected void processMessage(TradeOrderExecutedMessage message) {
        // 1) Pessimistic write lock 으로 order 조회
        Optional<Order> opt = orderRepository.findByIdForUpdate(message.orderId());
        if (opt.isEmpty()) {
            log.warn("[PortfolioUpdateConsumer] orderId not found - skip. orderId: {}", message.orderId());
            return;
        }
        Order order = opt.get();

        // 2) 멱등성 — (order_id, kis_execution_no) UNIQUE. 합성 키.
        String kisExecutionNo = synthesizeKisExecutionNo(message);
        if (orderExecutionRepository.existsByOrderIdAndKisExecutionNo(order.getId(), kisExecutionNo)) {
            log.info("[PortfolioUpdateConsumer] 중복 메시지 - skip. orderId: {}, kisExecNo: {}",
                    order.getId(), kisExecutionNo);
            return;
        }

        // 3) 종착 상태 가드 — RESERVED 도 거부 (변환 동기화 후에야 PENDING)
        if (order.getStatus() != OrderStatus.PENDING && order.getStatus() != OrderStatus.MODIFIED) {
            log.warn("[PortfolioUpdateConsumer] PENDING/MODIFIED 아님 — skip. orderId: {}, status: {}",
                    order.getId(), order.getStatus());
            return;
        }

        // 4) authoritative 계산 — 락 잡고 읽은 현재 상태 기준
        long prevFilled = order.getFilledQuantity() == null ? 0L : order.getFilledQuantity();
        long executedQty = message.executedQuantity();
        long newFilled = prevFilled + executedQty;
        boolean isFinalFill = newFilled >= order.getQuantity();

        // 5) order_executions INSERT (UNIQUE 위반 = 중복 — silent skip)
        try {
            orderExecutionRepository.save(OrderExecution.builder()
                    .userId(order.getUserId())
                    .orderId(order.getId())
                    .kisExecutionNo(kisExecutionNo)
                    .executedQuantity(executedQty)
                    .executedPrice(message.executedPrice())
                    .executedAt(message.executedAt())
                    .receivedAt(OffsetDateTime.now())
                    .build());
        } catch (DataIntegrityViolationException e) {
            log.info("[PortfolioUpdateConsumer] UNIQUE 충돌 — 중복 메시지 skip. orderId: {}, kisExecNo: {}",
                    order.getId(), kisExecutionNo);
            return;
        }

        // 6) Order 갱신 (markFilled — 가중평균 + isFinalFill 전이)
        order.markFilled(executedQty, message.executedPrice(), isFinalFill, message.executedAt());

        // 7) SELL 전량 체결 시 PnL 기록
        if (isFinalFill && order.getSide() == OrderSide.SELL) {
            insertPnlRecord(order);
        }

        // 8) SSE 발송
        sseEmitterManager.send(order.getUserId(),
                OrderSseEvent.executed(String.valueOf(order.getId()), order.getStockCode(),
                        order.getKisOrderNo()));

        // 9) Redis 동기화 이벤트 publish — AFTER_COMMIT listener 가 처리
        eventPublisher.publishEvent(new OrderExecutedEvent(
                order.getId(), order.getUserId(), order.getStockCode(),
                order.getSide(), isFinalFill));

        log.info("[PortfolioUpdateConsumer] 체결 반영 완료 - orderId: {}, side: {}, executedQty: {}, isFinal: {}",
                order.getId(), order.getSide(), executedQty, isFinalFill);
    }

    /**
     * SELL 전량 체결과 매칭되는 매수 주문을 찾아 trade_pnl_records INSERT.
     *
     * MVP 매칭: 동일 (user_id, stock_code) 의 가장 최근 FILLED BUY 주문, 아직 PnL 미매칭 row.
     * 다중 분할 매수 / 부분 매도 누적 대응은 followups (FIFO lot 매칭).
     */
    private void insertPnlRecord(Order sellOrder) {
        Optional<Order> buyOpt = orderRepository
                .findFirstByUserIdAndStockCodeAndSideAndStatusOrderByFilledAtDesc(
                        sellOrder.getUserId(), sellOrder.getStockCode(),
                        OrderSide.BUY, OrderStatus.FILLED);
        if (buyOpt.isEmpty()) {
            log.warn("[PortfolioUpdateConsumer] PnL 매칭 매수 주문 없음 — PnL 기록 skip. sellOrderId: {}",
                    sellOrder.getId());
            return;
        }
        Order buyOrder = buyOpt.get();
        if (tradePnlRecordRepository.existsByBuyOrderId(buyOrder.getId())) {
            log.warn("[PortfolioUpdateConsumer] 이미 매칭된 매수 주문 — PnL 기록 skip. buyOrderId: {}",
                    buyOrder.getId());
            return;
        }

        long holdingDays = buyOrder.getFilledAt() == null
                ? 0L
                : Duration.between(buyOrder.getFilledAt(), sellOrder.getFilledAt()).toDays();

        tradePnlRecordRepository.save(TradePnlRecord.builder()
                .stockCode(sellOrder.getStockCode())
                .userId(sellOrder.getUserId())
                .buyOrderId(buyOrder.getId())
                .sellOrderId(sellOrder.getId())
                .quantity(sellOrder.getFilledQuantity())
                .avgBuyPrice(buyOrder.getFilledAvgPrice())
                .sellPrice(sellOrder.getFilledAvgPrice())
                .commission(0L)
                .tax(0L)
                .holdingDays(holdingDays)
                .closedAt(sellOrder.getFilledAt())
                .build());
        log.info("[PortfolioUpdateConsumer] PnL 기록 INSERT - buyOrderId: {}, sellOrderId: {}",
                buyOrder.getId(), sellOrder.getId());
    }

    /**
     * KIS H0STCNI0 에 별도 체결번호 필드 없음 → 메시지 필드 합성으로 멱등키 생성.
     * (kis_order_no, executedAt 초단위, qty) 조합 — 동일 주문 내 동일 시각·수량 중복 통보를 차단.
     */
    private static String synthesizeKisExecutionNo(TradeOrderExecutedMessage message) {
        return message.kisOrderNo()
                + "_" + message.executedAt().toEpochSecond()
                + "_" + message.executedQuantity();
    }
}
