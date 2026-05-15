package com.modu.backend.domain.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modu.backend.domain.ai.entity.AiExecutionStatus;
import com.modu.backend.domain.ai.entity.AiJudgment;
import com.modu.backend.domain.ai.exception.AiErrorCode;
import com.modu.backend.domain.ai.repository.AiJudgmentRepository;
import com.modu.backend.domain.trading.entity.Order;
import com.modu.backend.domain.trading.entity.OrderSide;
import com.modu.backend.domain.trading.entity.OrderSource;
import com.modu.backend.domain.trading.entity.OrderType;
import com.modu.backend.domain.trading.entity.TradingRule;
import com.modu.backend.domain.trading.kafka.producer.TradeOrderProducer;
import com.modu.backend.domain.trading.position.entity.PositionThreshold;
import com.modu.backend.domain.trading.position.repository.PositionThresholdRepository;
import com.modu.backend.domain.trading.repository.OrderRepository;
import com.modu.backend.domain.trading.repository.TradingRuleRepository;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.kafka.dto.AiDecisionMessage;
import com.modu.backend.global.kafka.dto.TradeOrderMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SignalHandlerServiceTest {

    @Mock OrderRepository orderRepository;
    @Mock AiJudgmentRepository aiJudgmentRepository;
    @Mock TradingRuleRepository tradingRuleRepository;
    @Mock PositionThresholdRepository positionThresholdRepository;
    @Mock TradeOrderProducer tradeOrderProducer;

    @Spy ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    SignalHandlerService service;

    private static final Long USER_ID = 1L;
    private static final String STOCK = "005930";
    private static final String EVENT_ID = "market_event_abc";

    @BeforeEach
    void setUp() {
        // Order saveAndFlush 시 id 부여 시뮬레이션
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> {
            Order saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 999L);
            return saved;
        });
        // AiJudgment saveAndFlush 기본 정상
        when(aiJudgmentRepository.saveAndFlush(any(AiJudgment.class))).thenAnswer(invocation -> {
            AiJudgment saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 7L);
            return saved;
        });
    }

    // ───────────────────────────────────────────────────────────────────
    // READY 케이스
    // ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("READY + BUY: Order INSERT + Kafka 발행 + PositionThreshold 갱신 + AiJudgment INSERT")
    void readyBuyFullFlow() {
        AiDecisionMessage msg = buildMessage("completed", "trade", "buy", 700_000L, 70_000.0, 65_000.0, "low");
        PositionThreshold position = mockActivePositionWithUserPrice(80_000L, 60_000L);
        when(positionThresholdRepository.findByUserIdAndStockCodeAndIsActiveTrue(USER_ID, STOCK))
                .thenReturn(Optional.of(position));

        service.handle(msg);

        // AiJudgment 먼저 INSERT
        ArgumentCaptor<AiJudgment> judgmentCaptor = ArgumentCaptor.forClass(AiJudgment.class);
        verify(aiJudgmentRepository).saveAndFlush(judgmentCaptor.capture());
        AiJudgment savedJudgment = judgmentCaptor.getValue();
        assertThat(savedJudgment.getDecision()).isEqualTo("BUY");
        assertThat(savedJudgment.getExecutionStatus()).isEqualTo(AiExecutionStatus.READY);
        assertThat(savedJudgment.getSourceEventId()).isEqualTo(EVENT_ID);

        // Order INSERT (LIMIT/BUY/quantity=10)
        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).saveAndFlush(orderCaptor.capture());
        Order order = orderCaptor.getValue();
        assertThat(order.getSide()).isEqualTo(OrderSide.BUY);
        assertThat(order.getOrderType()).isEqualTo(OrderType.LIMIT);
        assertThat(order.getQuantity()).isEqualTo(10L); // 700,000 / 70,000
        assertThat(order.getLimitPrice()).isEqualTo(70_000L);
        assertThat(order.getSource()).isEqualTo(OrderSource.AI_DECISION);

        // Kafka 발행
        ArgumentCaptor<TradeOrderMessage> kafkaCaptor = ArgumentCaptor.forClass(TradeOrderMessage.class);
        verify(tradeOrderProducer).publishOrderSubmitted(kafkaCaptor.capture());
        assertThat(kafkaCaptor.getValue().source()).isEqualTo(OrderSource.AI_DECISION.name());
        assertThat(kafkaCaptor.getValue().side()).isEqualTo(OrderSide.BUY.name());

        // PositionThreshold 갱신 + orderId linked
        assertThat(position.getAiTargetPrice()).isEqualTo(70_000L);
        assertThat(position.getAiStopLossPrice()).isEqualTo(65_000L);
        assertThat(savedJudgment.getOrderId()).isEqualTo(999L);
    }

    @Test
    @DisplayName("READY + SELL: Order INSERT + Kafka 발행. PositionThreshold 갱신 안 함")
    void readySellSkipsPositionUpdate() {
        AiDecisionMessage msg = buildMessage("completed", "trade", "sell", 700_000L, 70_000.0, 65_000.0, "low");

        service.handle(msg);

        verify(orderRepository).saveAndFlush(any(Order.class));
        verify(tradeOrderProducer).publishOrderSubmitted(any());
        verify(positionThresholdRepository, never()).findByUserIdAndStockCodeAndIsActiveTrue(anyLong(), anyString());
    }

    @Test
    @DisplayName("READY + BUY + PositionThreshold 활성 row 없음: Order/Kafka 만 발행, 갱신 skip")
    void readyBuyNoActivePositionThreshold() {
        AiDecisionMessage msg = buildMessage("completed", "trade", "buy", 700_000L, 70_000.0, 65_000.0, "low");
        when(positionThresholdRepository.findByUserIdAndStockCodeAndIsActiveTrue(USER_ID, STOCK))
                .thenReturn(Optional.empty());

        service.handle(msg);

        verify(orderRepository).saveAndFlush(any(Order.class));
        verify(tradeOrderProducer).publishOrderSubmitted(any());
    }

    // ───────────────────────────────────────────────────────────────────
    // HOLD_ONLY / BLOCKED 케이스
    // ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("flow_status=hold: HOLD_ONLY 로 AiJudgment INSERT, Order/Kafka 없음")
    void flowStatusHold() {
        AiDecisionMessage msg = buildMessage("hold", "hold", null, null, null, null, null);

        service.handle(msg);

        ArgumentCaptor<AiJudgment> captor = ArgumentCaptor.forClass(AiJudgment.class);
        verify(aiJudgmentRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getExecutionStatus()).isEqualTo(AiExecutionStatus.HOLD_ONLY);
        assertThat(captor.getValue().getDecision()).isEqualTo("HOLD");
        verify(orderRepository, never()).saveAndFlush(any());
        verify(tradeOrderProducer, never()).publishOrderSubmitted(any());
    }

    @Test
    @DisplayName("flow_status=blocked: BLOCKED 로 INSERT, Order/Kafka 없음")
    void flowStatusBlocked() {
        AiDecisionMessage msg = buildMessage("blocked", "trade", "buy", 500_000L, 50_000.0, 45_000.0, "low");

        service.handle(msg);

        ArgumentCaptor<AiJudgment> captor = ArgumentCaptor.forClass(AiJudgment.class);
        verify(aiJudgmentRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getExecutionStatus()).isEqualTo(AiExecutionStatus.BLOCKED);
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("flow_status=failed: BLOCKED 로 INSERT (기록 보존)")
    void flowStatusFailedMapsToBlocked() {
        AiDecisionMessage msg = buildMessage("failed", "trade", "buy", 500_000L, 50_000.0, 45_000.0, "low");

        service.handle(msg);

        ArgumentCaptor<AiJudgment> captor = ArgumentCaptor.forClass(AiJudgment.class);
        verify(aiJudgmentRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getExecutionStatus()).isEqualTo(AiExecutionStatus.BLOCKED);
    }

    @Test
    @DisplayName("flow_status=running: ApiException — UNSUPPORTED_FLOW_STATUS")
    void flowStatusRunningThrows() {
        AiDecisionMessage msg = buildMessage("running", "trade", "buy", 500_000L, 50_000.0, 45_000.0, "low");

        assertThatThrownBy(() -> service.handle(msg))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(AiErrorCode.UNSUPPORTED_FLOW_STATUS);

        verify(aiJudgmentRepository, never()).saveAndFlush(any());
    }

    // ───────────────────────────────────────────────────────────────────
    // APPROVAL_REQUIRED 케이스
    // ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("risk_level=high: APPROVAL_REQUIRED 로 INSERT, Order/Kafka 없음")
    void riskLevelHighTriggersApprovalRequired() {
        AiDecisionMessage msg = buildMessage("completed", "trade", "buy", 500_000L, 50_000.0, 45_000.0, "high");

        service.handle(msg);

        ArgumentCaptor<AiJudgment> captor = ArgumentCaptor.forClass(AiJudgment.class);
        verify(aiJudgmentRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getExecutionStatus()).isEqualTo(AiExecutionStatus.APPROVAL_REQUIRED);
        verify(orderRepository, never()).saveAndFlush(any());
        verify(tradeOrderProducer, never()).publishOrderSubmitted(any());
    }

    @Test
    @DisplayName("BUY 누적 한도 초과: APPROVAL_REQUIRED 로 INSERT")
    void dailyBuyLimitExceededTriggersApprovalRequired() {
        AiDecisionMessage msg = buildMessage("completed", "trade", "buy", 700_000L, 70_000.0, 65_000.0, "low");
        TradingRule rule = TradingRule.builder()
                .userId(USER_ID)
                .stopLossPct(5L)
                .takeProfitPct(10L)
                .maxDailyOrderCount(10L)
                .dailyLossLimitAmount(1_000_000L)
                .build();
        when(tradingRuleRepository.findById(USER_ID)).thenReturn(Optional.of(rule));
        when(orderRepository.sumTodayBuyAmount(USER_ID)).thenReturn(500_000L); // 누적 50만 + 신규 70만 = 120만 > 100만

        service.handle(msg);

        ArgumentCaptor<AiJudgment> captor = ArgumentCaptor.forClass(AiJudgment.class);
        verify(aiJudgmentRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getExecutionStatus()).isEqualTo(AiExecutionStatus.APPROVAL_REQUIRED);
        verify(orderRepository, never()).saveAndFlush(any(Order.class));
    }

    @Test
    @DisplayName("BUY 한도 이내: READY (한도 통과)")
    void dailyBuyLimitWithinReady() {
        AiDecisionMessage msg = buildMessage("completed", "trade", "buy", 300_000L, 30_000.0, 25_000.0, "low");
        TradingRule rule = TradingRule.builder()
                .userId(USER_ID)
                .stopLossPct(5L)
                .takeProfitPct(10L)
                .maxDailyOrderCount(10L)
                .dailyLossLimitAmount(1_000_000L)
                .build();
        when(tradingRuleRepository.findById(USER_ID)).thenReturn(Optional.of(rule));
        when(orderRepository.sumTodayBuyAmount(USER_ID)).thenReturn(500_000L); // 누적 50만 + 신규 30만 = 80만 < 100만

        service.handle(msg);

        verify(orderRepository).saveAndFlush(any(Order.class));
        verify(tradeOrderProducer).publishOrderSubmitted(any());
    }

    @Test
    @DisplayName("TradingRule row 없음 + BUY: 한도 체크 skip → READY 진입")
    void tradingRuleAbsentReady() {
        AiDecisionMessage msg = buildMessage("completed", "trade", "buy", 300_000L, 30_000.0, 25_000.0, "low");
        when(tradingRuleRepository.findById(USER_ID)).thenReturn(Optional.empty());

        service.handle(msg);

        verify(orderRepository).saveAndFlush(any(Order.class));
    }

    // ───────────────────────────────────────────────────────────────────
    // 멱등성
    // ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("중복 메시지 (사전 SELECT 가 true): silent skip, INSERT/Order/Kafka 없음")
    void duplicateMessageSilentSkip() {
        AiDecisionMessage msg = buildMessage("completed", "trade", "buy", 700_000L, 70_000.0, 65_000.0, "low");
        when(aiJudgmentRepository.existsByUserIdAndSourceEventId(USER_ID, "evt-buy-001"))
                .thenReturn(true);

        // 메시지 source_event_id 가 buildMessage 기본값(EVENT_ID=market_event_abc) 라
        // 위 stub 이 매칭되지 않을 수 있어 어떤 sourceEventId 든 true 반환으로 설정
        when(aiJudgmentRepository.existsByUserIdAndSourceEventId(any(), anyString())).thenReturn(true);

        service.handle(msg); // 예외 던지지 않음

        verify(aiJudgmentRepository, never()).saveAndFlush(any(AiJudgment.class));
        verify(orderRepository, never()).saveAndFlush(any());
        verify(tradeOrderProducer, never()).publishOrderSubmitted(any());
    }

    // ───────────────────────────────────────────────────────────────────
    // 검증 실패
    // ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("필수 필드 누락: INVALID_DECISION_MESSAGE")
    void missingRequiredFieldThrows() {
        AiDecisionMessage msg = new AiDecisionMessage(
                null, EVENT_ID, STOCK, OffsetDateTime.now(), null,
                null, null, null, "completed"
        );

        assertThatThrownBy(() -> service.handle(msg))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(AiErrorCode.INVALID_DECISION_MESSAGE);
    }

    @Test
    @DisplayName("READY 결정인데 target_price 누락: INVALID_ORDER_PARAMS")
    void readyMissingTargetPriceThrows() {
        AiDecisionMessage msg = buildMessage("completed", "trade", "buy", 700_000L, null, 65_000.0, "low");

        assertThatThrownBy(() -> service.handle(msg))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(AiErrorCode.INVALID_ORDER_PARAMS);

        // AiJudgment 는 INSERT 됨 (검증은 publishOrder 단계에서) → INSERT 후 예외 → 트랜잭션 롤백
        verify(aiJudgmentRepository).saveAndFlush(any());
    }

    // ───────────────────────────────────────────────────────────────────
    // 헬퍼
    // ───────────────────────────────────────────────────────────────────

    private AiDecisionMessage buildMessage(
            String flowStatus, String action, String side, Long orderAmount,
            Double targetPrice, Double stopLossPrice, String riskLevel
    ) {
        AiDecisionMessage.FinalDecision fd = "hold".equalsIgnoreCase(action) || action == null
                ? new AiDecisionMessage.FinalDecision("hold", null, null, null, null, "보류", 0.5, riskLevel)
                : new AiDecisionMessage.FinalDecision(
                        action, side, orderAmount, targetPrice, stopLossPrice,
                        "AI 판단 사유", 0.78, riskLevel
                );
        JsonNode indicators = objectMapper.createObjectNode().put("rsi", 48.2);
        AiDecisionMessage.Debate debate = new AiDecisionMessage.Debate(
                "Bull: ...", "Bear: ...", "bull",
                objectMapper.createArrayNode().add("technical_signal")
        );
        return new AiDecisionMessage(
                USER_ID, EVENT_ID, STOCK,
                OffsetDateTime.parse("2026-05-14T02:07:00+09:00"),
                null,
                fd, debate, indicators, flowStatus
        );
    }

    private PositionThreshold mockActivePositionWithUserPrice(Long userTakeProfit, Long userStop) {
        PositionThreshold p = PositionThreshold.builder()
                .userId(USER_ID)
                .stockCode(STOCK)
                .sourceOrderId(100L)
                .quantity(10L)
                .avgEntryPrice(70_000L)
                .userTakeProfitPrice(userTakeProfit)
                .userStopLossPrice(userStop)
                .activeTargetPrice(userTakeProfit)
                .activeStopLossPrice(userStop)
                .build();
        ReflectionTestUtils.setField(p, "id", 1L);
        return p;
    }
}
