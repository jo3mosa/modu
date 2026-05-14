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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PositionTriggerExecutorTest {

    @Mock OrderRepository orderRepository;
    @Mock PositionThresholdRepository positionThresholdRepository;
    @Mock TradeOrderProducer tradeOrderProducer;

    @InjectMocks
    PositionTriggerExecutor executor;

    private PositionThreshold position;

    @BeforeEach
    void setUp() {
        position = PositionThreshold.builder()
                .userId(7L)
                .stockCode("005930")
                .sourceOrderId(100L)
                .quantity(10L)
                .avgEntryPrice(70000L)
                .activeStopLossPrice(68000L)
                .userStopLossPrice(68000L)
                .build();
        ReflectionTestUtils.setField(position, "id", 1L);

        // saveAndFlush 시 Order id 부여 시뮬레이션
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> {
            Order saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 999L);
            return saved;
        });
    }

    @Test
    @DisplayName("STOP_LOSS 트리거 시 Order INSERT(MARKET, SELL, 전량) + Kafka 발행 + markTriggered 수행")
    void executeStopLoss() {
        when(positionThresholdRepository.findById(1L)).thenReturn(Optional.of(position));

        executor.execute(1L, PositionTriggerReason.USER_STOP_LOSS);

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).saveAndFlush(orderCaptor.capture());
        Order saved = orderCaptor.getValue();
        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(saved.getStockCode()).isEqualTo("005930");
        assertThat(saved.getSide()).isEqualTo(OrderSide.SELL);
        assertThat(saved.getOrderType()).isEqualTo(OrderType.MARKET);
        assertThat(saved.getQuantity()).isEqualTo(10L);
        assertThat(saved.getLimitPrice()).isNull();
        assertThat(saved.getSource()).isEqualTo(OrderSource.STOP_LOSS);
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(saved.getIdempotencyKey()).isNotBlank();

        ArgumentCaptor<TradeOrderMessage> msgCaptor = ArgumentCaptor.forClass(TradeOrderMessage.class);
        verify(tradeOrderProducer).publishOrderSubmitted(msgCaptor.capture());
        TradeOrderMessage msg = msgCaptor.getValue();
        assertThat(msg.orderId()).isEqualTo(saved.getIdempotencyKey());
        assertThat(msg.userId()).isEqualTo(7L);
        assertThat(msg.side()).isEqualTo(OrderSide.SELL.name());
        assertThat(msg.orderType()).isEqualTo(OrderType.MARKET.name());
        assertThat(msg.source()).isEqualTo(OrderSource.STOP_LOSS.name());
        assertThat(msg.limitPrice()).isNull();
        assertThat(msg.parentOrderId()).isNull();
        assertThat(msg.ruleHistoryId()).isNull();

        assertThat(position.isActive()).isFalse();
        assertThat(position.getTriggeredReason()).isEqualTo(PositionTriggerReason.USER_STOP_LOSS);
        assertThat(position.getLastOrderId()).isEqualTo(999L);
        assertThat(position.getClosedAt()).isNotNull();
    }

    @Test
    @DisplayName("TAKE_PROFIT 트리거 시 source=TAKE_PROFIT 으로 발행")
    void executeTakeProfit() {
        when(positionThresholdRepository.findById(1L)).thenReturn(Optional.of(position));

        executor.execute(1L, PositionTriggerReason.AI_TAKE_PROFIT);

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).saveAndFlush(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getSource()).isEqualTo(OrderSource.TAKE_PROFIT);
        assertThat(position.getTriggeredReason()).isEqualTo(PositionTriggerReason.AI_TAKE_PROFIT);
    }

    @Test
    @DisplayName("PositionThreshold 가 이미 비활성이면 아무 작업 안 함")
    void skipWhenAlreadyInactive() {
        position.markTriggered(PositionTriggerReason.USER_STOP_LOSS, 500L);
        when(positionThresholdRepository.findById(1L)).thenReturn(Optional.of(position));

        executor.execute(1L, PositionTriggerReason.USER_STOP_LOSS);

        verify(orderRepository, never()).saveAndFlush(any());
        verify(tradeOrderProducer, never()).publishOrderSubmitted(any());
    }

    @Test
    @DisplayName("PositionThreshold 가 존재하지 않으면 아무 작업 안 함")
    void skipWhenNotFound() {
        when(positionThresholdRepository.findById(1L)).thenReturn(Optional.empty());

        executor.execute(1L, PositionTriggerReason.USER_STOP_LOSS);

        verify(orderRepository, never()).saveAndFlush(any());
        verify(tradeOrderProducer, never()).publishOrderSubmitted(any());
    }
}
