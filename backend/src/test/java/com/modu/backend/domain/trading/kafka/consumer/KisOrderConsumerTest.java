package com.modu.backend.domain.trading.kafka.consumer;

import com.modu.backend.domain.trading.entity.Order;
import com.modu.backend.domain.trading.entity.OrderSide;
import com.modu.backend.domain.trading.entity.OrderSource;
import com.modu.backend.domain.trading.entity.OrderStatus;
import com.modu.backend.domain.trading.entity.OrderType;
import com.modu.backend.domain.trading.repository.OrderRepository;
import com.modu.backend.domain.trading.service.OrderDispatchService;
import com.modu.backend.global.kafka.dto.TradeOrderMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KisOrderConsumer 단위 테스트
 *
 * 515caf0 (2026-05-18) 슬림화 이후 컨슈머의 책임은:
 *  1. message → orderId 매칭 (orderRepository)
 *  2. 매칭되면 {@link OrderDispatchService#dispatch(Long)} 에 위임
 *  3. 어떤 결과든 finally 에서 ack
 *
 * KIS 호출 / SSE / Kill Switch 등 dispatch 분기 검증은
 * {@link com.modu.backend.domain.trading.service.OrderDispatchServiceTest} 책임.
 */
@ExtendWith(MockitoExtension.class)
class KisOrderConsumerTest {

    @Mock OrderRepository orderRepository;
    @Mock OrderDispatchService orderDispatchService;
    @Mock Acknowledgment ack;

    @InjectMocks KisOrderConsumer consumer;

    @Test
    @DisplayName("정상 — order 매칭되면 dispatch 호출 + ack")
    void 정상_dispatch_위임() {
        TradeOrderMessage msg = sampleMessage("uuid-1", 1L);
        Order order = pendingOrder(100L, "uuid-1");
        when(orderRepository.findByUserIdAndIdempotencyKey(1L, "uuid-1"))
                .thenReturn(Optional.of(order));

        consumer.onMessage(msg, ack);

        verify(orderDispatchService).dispatch(eq(100L));
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("order 미존재 — dispatch 호출 안 함 + ack")
    void order_미존재_dispatch_생략() {
        TradeOrderMessage msg = sampleMessage("uuid-x", 1L);
        when(orderRepository.findByUserIdAndIdempotencyKey(1L, "uuid-x"))
                .thenReturn(Optional.empty());

        consumer.onMessage(msg, ack);

        verify(orderDispatchService, never()).dispatch(eq(0L));
        verify(orderDispatchService, never()).dispatch(eq(100L));
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("dispatch 예외 — ack 보장 (finally)")
    void dispatch_예외_ack_보장() {
        TradeOrderMessage msg = sampleMessage("uuid-1", 1L);
        Order order = pendingOrder(100L, "uuid-1");
        when(orderRepository.findByUserIdAndIdempotencyKey(1L, "uuid-1"))
                .thenReturn(Optional.of(order));
        doThrow(new RuntimeException("dispatch boom"))
                .when(orderDispatchService).dispatch(eq(100L));

        consumer.onMessage(msg, ack);

        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("orderRepository 예외 — dispatch 호출 안 함 + ack 보장")
    void repository_예외_ack_보장() {
        TradeOrderMessage msg = sampleMessage("uuid-1", 1L);
        when(orderRepository.findByUserIdAndIdempotencyKey(1L, "uuid-1"))
                .thenThrow(new RuntimeException("DB down"));

        consumer.onMessage(msg, ack);

        verify(orderDispatchService, never()).dispatch(eq(100L));
        verify(ack).acknowledge();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private TradeOrderMessage sampleMessage(String orderId, Long userId) {
        return TradeOrderMessage.of(
                orderId, null, userId, "005930",
                OrderSide.BUY, OrderType.LIMIT,
                10L, 70000L, OrderSource.MANUAL, null, OffsetDateTime.now()
        );
    }

    private Order pendingOrder(Long id, String idempotencyKey) {
        Order order = Order.builder()
                .userId(1L)
                .stockCode("005930")
                .side(OrderSide.BUY)
                .orderType(OrderType.LIMIT)
                .quantity(10L)
                .limitPrice(70000L)
                .status(OrderStatus.PENDING)
                .source(OrderSource.MANUAL)
                .idempotencyKey(idempotencyKey)
                .build();
        ReflectionTestUtils.setField(order, "id", id);
        return order;
    }
}
