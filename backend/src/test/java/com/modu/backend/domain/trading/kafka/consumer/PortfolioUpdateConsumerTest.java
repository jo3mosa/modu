package com.modu.backend.domain.trading.kafka.consumer;

import com.modu.backend.domain.ai.repository.AiJudgmentRepository;
import com.modu.backend.domain.trading.entity.Order;
import com.modu.backend.domain.trading.entity.OrderExecution;
import com.modu.backend.domain.trading.entity.OrderSide;
import com.modu.backend.domain.trading.entity.OrderSource;
import com.modu.backend.domain.trading.entity.OrderStatus;
import com.modu.backend.domain.trading.entity.OrderType;
import com.modu.backend.domain.trading.entity.TradePnlRecord;
import com.modu.backend.domain.trading.execution.event.OrderExecutedEvent;
import com.modu.backend.domain.trading.execution.producer.TradeSettledProducer;
import com.modu.backend.domain.trading.repository.OrderExecutionRepository;
import com.modu.backend.domain.trading.repository.OrderRepository;
import com.modu.backend.domain.trading.repository.TradePnlRecordRepository;
import com.modu.backend.domain.trading.sse.OrderSseEmitterManager;
import com.modu.backend.global.kafka.dto.TradeOrderExecutedMessage;
import com.modu.backend.global.kafka.dto.TradeSettledMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.support.Acknowledgment;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PortfolioUpdateConsumer — S14P31B106-291 핵심 비즈니스 로직 검증.
 *
 * 분기:
 *  1. 부분 체결 (isFinalFill=false) — markFilled 누적, FILLED 미전이
 *  2. 전량 매수 체결 — FILLED 전이, PnL 없음 (BUY 는 PnL 미생성)
 *  3. 전량 매도 체결 — FILLED + PnL INSERT + trade.settled 발행
 *  4. 멱등 중복 메시지 — skip
 *  5. RESERVED 상태 메시지 도착 → skip
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PortfolioUpdateConsumerTest {

    @Mock OrderRepository orderRepository;
    @Mock OrderExecutionRepository orderExecutionRepository;
    @Mock TradePnlRecordRepository tradePnlRecordRepository;
    @Mock OrderSseEmitterManager sseEmitterManager;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock TradeSettledProducer tradeSettledProducer;
    @Mock AiJudgmentRepository aiJudgmentRepository;
    @Mock Acknowledgment ack;

    @InjectMocks
    PortfolioUpdateConsumer consumer;

    private static final Long ORDER_ID = 100L;
    private static final Long USER_ID = 1L;
    private static final String STOCK = "005930";

    @BeforeEach
    void setUp() {
        when(orderExecutionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tradePnlRecordRepository.save(any())).thenAnswer(inv -> {
            TradePnlRecord pnl = inv.getArgument(0);
            // id 부여 시뮬레이션 — 실 DB 와 유사하게
            java.lang.reflect.Field idField = TradePnlRecord.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(pnl, 999L);
            return pnl;
        });
    }

    // ──────────────────────────────────────────────────────────
    // 부분 체결
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("부분 체결 — markFilled 누적, status=PENDING 유지, settled 미발행")
    void partialFill() {
        Order order = buyOrder(100L); // 100주 주문
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(orderExecutionRepository.existsByOrderIdAndKisExecutionNo(anyLong(), anyString()))
                .thenReturn(false);

        consumer.onMessage(message(30L, OrderSide.BUY), ack);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getFilledQuantity()).isEqualTo(30L);
        verify(tradeSettledProducer, never()).publish(any());
        verify(ack).acknowledge();
    }

    // ──────────────────────────────────────────────────────────
    // 전량 매수 체결
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("전량 매수 체결 — FILLED 전이 + PnL 없음 + settled 미발행")
    void finalBuyFill() {
        Order order = buyOrder(10L);
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(orderExecutionRepository.existsByOrderIdAndKisExecutionNo(anyLong(), anyString()))
                .thenReturn(false);

        consumer.onMessage(message(10L, OrderSide.BUY), ack);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
        verify(tradePnlRecordRepository, never()).save(any());
        verify(tradeSettledProducer, never()).publish(any());
        verify(eventPublisher).publishEvent(any(OrderExecutedEvent.class));
    }

    // ──────────────────────────────────────────────────────────
    // 전량 매도 체결 + PnL + settled
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("전량 매도 체결 — FILLED + PnL INSERT + trade.settled 발행")
    void finalSellFillWithPnl() {
        Order sellOrder = sellOrder(10L);
        Order buyOrder  = buyOrderFilled(10L, 70000L);
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(sellOrder));
        when(orderExecutionRepository.existsByOrderIdAndKisExecutionNo(anyLong(), anyString()))
                .thenReturn(false);
        when(orderRepository.findFirstByUserIdAndStockCodeAndSideAndStatusOrderByFilledAtDesc(
                eq(USER_ID), eq(STOCK), eq(OrderSide.BUY), eq(OrderStatus.FILLED)))
                .thenReturn(Optional.of(buyOrder));
        when(tradePnlRecordRepository.existsByBuyOrderId(buyOrder.getId())).thenReturn(false);
        when(aiJudgmentRepository.findFirstByUserIdAndOrderIdOrderByJudgedAtDesc(anyLong(), anyLong()))
                .thenReturn(Optional.empty()); // 수동 매수 가정

        consumer.onMessage(message(10L, OrderSide.SELL, 75000L), ack);

        assertThat(sellOrder.getStatus()).isEqualTo(OrderStatus.FILLED);
        verify(tradePnlRecordRepository).save(any(TradePnlRecord.class));

        ArgumentCaptor<TradeSettledMessage> captor = ArgumentCaptor.forClass(TradeSettledMessage.class);
        verify(tradeSettledProducer).publish(captor.capture());
        TradeSettledMessage published = captor.getValue();
        assertThat(published.userId()).isEqualTo(USER_ID);
        assertThat(published.tradePnlRecordId()).isEqualTo(999L);
        assertThat(published.rawReturn()).isCloseTo((75000.0 - 70000.0) / 70000.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(published.alphaReturn()).isNull(); // 시장 지수 미산출
    }

    @Test
    @DisplayName("전량 매도 체결 — 매칭 BUY 없음 → PnL skip + settled 미발행")
    void finalSellFillNoMatchingBuy() {
        Order sellOrder = sellOrder(10L);
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(sellOrder));
        when(orderExecutionRepository.existsByOrderIdAndKisExecutionNo(anyLong(), anyString()))
                .thenReturn(false);
        when(orderRepository.findFirstByUserIdAndStockCodeAndSideAndStatusOrderByFilledAtDesc(
                eq(USER_ID), eq(STOCK), eq(OrderSide.BUY), eq(OrderStatus.FILLED)))
                .thenReturn(Optional.empty());

        consumer.onMessage(message(10L, OrderSide.SELL), ack);

        assertThat(sellOrder.getStatus()).isEqualTo(OrderStatus.FILLED); // markFilled 는 됨
        verify(tradePnlRecordRepository, never()).save(any());
        verify(tradeSettledProducer, never()).publish(any());
    }

    // ──────────────────────────────────────────────────────────
    // 멱등 / 가드
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("중복 메시지 — existsByOrderIdAndKisExecutionNo true → skip")
    void duplicateMessageSkipped() {
        Order order = buyOrder(10L);
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(orderExecutionRepository.existsByOrderIdAndKisExecutionNo(anyLong(), anyString()))
                .thenReturn(true);

        consumer.onMessage(message(10L, OrderSide.BUY), ack);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getFilledQuantity()).isEqualTo(0L);
        verify(orderExecutionRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("RESERVED 상태 메시지 — skip (변환 동기화 전 race)")
    void reservedStatusSkipped() {
        Order order = buyOrder(10L);
        order.markReserved("RSVN_001", OffsetDateTime.now());
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(orderExecutionRepository.existsByOrderIdAndKisExecutionNo(anyLong(), anyString()))
                .thenReturn(false);

        consumer.onMessage(message(10L, OrderSide.BUY), ack);

        // RESERVED 그대로
        assertThat(order.getStatus()).isEqualTo(OrderStatus.RESERVED);
        verify(orderExecutionRepository, never()).save(any());
    }

    @Test
    @DisplayName("orderId 미발견 — skip + ack")
    void orderNotFound() {
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.empty());

        consumer.onMessage(message(10L, OrderSide.BUY), ack);

        verify(ack).acknowledge();
        verify(orderExecutionRepository, never()).save(any());
    }

    // ──────────────────────────────────────────────────────────
    // 헬퍼
    // ──────────────────────────────────────────────────────────

    private static Order buyOrder(long quantity) {
        Order order = Order.builder()
                .userId(USER_ID)
                .stockCode(STOCK)
                .side(OrderSide.BUY)
                .orderType(OrderType.LIMIT)
                .quantity(quantity)
                .limitPrice(70000L)
                .status(OrderStatus.PENDING)
                .source(OrderSource.AI_DECISION)
                .idempotencyKey("idem-buy")
                .build();
        setField(order, "id", ORDER_ID);
        setField(order, "kisOrderNo", "0000002891");
        return order;
    }

    private static Order buyOrderFilled(long quantity, long avgPrice) {
        Order order = buyOrder(quantity);
        setField(order, "filledQuantity", quantity);
        setField(order, "filledAvgPrice", avgPrice);
        setField(order, "status", OrderStatus.FILLED);
        setField(order, "filledAt", OffsetDateTime.now().minusDays(1));
        setField(order, "id", 50L);
        return order;
    }

    private static Order sellOrder(long quantity) {
        Order order = Order.builder()
                .userId(USER_ID)
                .stockCode(STOCK)
                .side(OrderSide.SELL)
                .orderType(OrderType.LIMIT)
                .quantity(quantity)
                .limitPrice(75000L)
                .status(OrderStatus.PENDING)
                .source(OrderSource.AI_DECISION)
                .idempotencyKey("idem-sell")
                .build();
        setField(order, "id", ORDER_ID);
        setField(order, "kisOrderNo", "0000002892");
        return order;
    }

    private static TradeOrderExecutedMessage message(long qty, OrderSide side) {
        return message(qty, side, 70000L);
    }

    private static TradeOrderExecutedMessage message(long qty, OrderSide side, long price) {
        return TradeOrderExecutedMessage.of(
                ORDER_ID, "0000002891", USER_ID, STOCK,
                side, qty, price, 0L, false,
                OffsetDateTime.now()
        );
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field f = findField(target.getClass(), fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static java.lang.reflect.Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { c = c.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }
}
