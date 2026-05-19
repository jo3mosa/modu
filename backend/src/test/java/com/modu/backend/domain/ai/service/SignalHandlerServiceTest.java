package com.modu.backend.domain.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modu.backend.domain.ai.entity.AiExecutionStatus;
import com.modu.backend.domain.ai.entity.AiJudgment;
import com.modu.backend.domain.ai.exception.AiErrorCode;
import com.modu.backend.domain.ai.repository.AiJudgmentRepository;
import com.modu.backend.domain.ai.repository.StockRiskTierRedisRepository;
import com.modu.backend.domain.investment.entity.InvestmentProfile;
import com.modu.backend.domain.investment.repository.InvestmentProfileRepository;
import com.modu.backend.domain.strategy.entity.AutoTradeSettings;
import com.modu.backend.domain.strategy.entity.AutoTradeStatus;
import com.modu.backend.domain.strategy.repository.AutoTradeSettingsRepository;
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
    @Mock AutoTradeSettingsRepository autoTradeSettingsRepository;
    @Mock PortfolioCheckService portfolioCheckService;
    @Mock TradeOrderProducer tradeOrderProducer;
    @Mock StockRiskTierRedisRepository stockRiskTierRedisRepository;
    @Mock InvestmentProfileRepository investmentProfileRepository;

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
        // 기본 — 자동매매 ACTIVE
        AutoTradeSettings activeSettings = AutoTradeSettings.builder()
                .userId(USER_ID)
                .autoTradeStatus(AutoTradeStatus.ACTIVE)
                .build();
        when(autoTradeSettingsRepository.findById(USER_ID)).thenReturn(Optional.of(activeSettings));
        // 기본 — 잔고/보유 충분
        when(portfolioCheckService.hasSufficientCash(any(), anyLong())).thenReturn(true);
        when(portfolioCheckService.hasSufficientHolding(any(), anyString(), anyLong())).thenReturn(true);
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
    // AutoTradeStatus / 사전 잔고 검증 (S14P31B106-292)
    // ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AutoTradeStatus=INACTIVE: BLOCKED 로 INSERT, Order/Kafka 없음")
    void inactiveBlocks() {
        AutoTradeSettings inactive = AutoTradeSettings.builder()
                .userId(USER_ID).autoTradeStatus(AutoTradeStatus.INACTIVE).build();
        when(autoTradeSettingsRepository.findById(USER_ID)).thenReturn(Optional.of(inactive));
        AiDecisionMessage msg = buildMessage("completed", "trade", "buy", 700_000L, 70_000.0, 65_000.0, "low");

        service.handle(msg);

        ArgumentCaptor<AiJudgment> captor = ArgumentCaptor.forClass(AiJudgment.class);
        verify(aiJudgmentRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getExecutionStatus()).isEqualTo(AiExecutionStatus.BLOCKED);
        verify(orderRepository, never()).saveAndFlush(any());
        verify(tradeOrderProducer, never()).publishOrderSubmitted(any());
    }

    @Test
    @DisplayName("AutoTradeSettings row 없음 (신규 사용자): BLOCKED")
    void autoTradeSettingsAbsentBlocks() {
        when(autoTradeSettingsRepository.findById(USER_ID)).thenReturn(Optional.empty());
        AiDecisionMessage msg = buildMessage("completed", "trade", "buy", 700_000L, 70_000.0, 65_000.0, "low");

        service.handle(msg);

        ArgumentCaptor<AiJudgment> captor = ArgumentCaptor.forClass(AiJudgment.class);
        verify(aiJudgmentRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getExecutionStatus()).isEqualTo(AiExecutionStatus.BLOCKED);
        verify(orderRepository, never()).saveAndFlush(any());
        verify(tradeOrderProducer, never()).publishOrderSubmitted(any());
    }

    @Test
    @DisplayName("AutoTradeStatus=KILL_SWITCHED: BLOCKED")
    void killSwitchedBlocks() {
        AutoTradeSettings ks = AutoTradeSettings.builder()
                .userId(USER_ID).autoTradeStatus(AutoTradeStatus.KILL_SWITCHED).build();
        when(autoTradeSettingsRepository.findById(USER_ID)).thenReturn(Optional.of(ks));
        AiDecisionMessage msg = buildMessage("completed", "trade", "buy", 700_000L, 70_000.0, 65_000.0, "low");

        service.handle(msg);

        ArgumentCaptor<AiJudgment> captor = ArgumentCaptor.forClass(AiJudgment.class);
        verify(aiJudgmentRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getExecutionStatus()).isEqualTo(AiExecutionStatus.BLOCKED);
        verify(orderRepository, never()).saveAndFlush(any());
        verify(tradeOrderProducer, never()).publishOrderSubmitted(any());
    }

    @Test
    @DisplayName("BUY 잔고 부족: BLOCKED")
    void buyInsufficientCashBlocks() {
        when(portfolioCheckService.hasSufficientCash(USER_ID, 700_000L)).thenReturn(false);
        AiDecisionMessage msg = buildMessage("completed", "trade", "buy", 700_000L, 70_000.0, 65_000.0, "low");

        service.handle(msg);

        ArgumentCaptor<AiJudgment> captor = ArgumentCaptor.forClass(AiJudgment.class);
        verify(aiJudgmentRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getExecutionStatus()).isEqualTo(AiExecutionStatus.BLOCKED);
        verify(orderRepository, never()).saveAndFlush(any());
        verify(tradeOrderProducer, never()).publishOrderSubmitted(any());
    }

    @Test
    @DisplayName("SELL 보유 부족: BLOCKED")
    void sellInsufficientHoldingBlocks() {
        when(portfolioCheckService.hasSufficientHolding(eq(USER_ID), eq(STOCK), anyLong())).thenReturn(false);
        AiDecisionMessage msg = buildMessage("completed", "trade", "sell", 700_000L, 70_000.0, 65_000.0, "low");

        service.handle(msg);

        ArgumentCaptor<AiJudgment> captor = ArgumentCaptor.forClass(AiJudgment.class);
        verify(aiJudgmentRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getExecutionStatus()).isEqualTo(AiExecutionStatus.BLOCKED);
        verify(orderRepository, never()).saveAndFlush(any());
        verify(tradeOrderProducer, never()).publishOrderSubmitted(any());
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
                null, null, null, "completed", null
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
    // S14P31B106-354 — isHolder 분기 + RECOMMENDED
    // ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("isHolder=false + BUY → RECOMMENDED, stock_tier / matched_risk_grade 채워짐, 5분 만료 세팅")
    void nonHolderBuyResolvesToRecommended() {
        when(stockRiskTierRedisRepository.get(STOCK)).thenReturn(3);
        when(investmentProfileRepository.findById(USER_ID))
                .thenReturn(Optional.of(buildProfileWithGrade("ACTIVE")));

        AiDecisionMessage msg = nonHolderMessage("buy", 700_000L, 70_000.0, 65_000.0, "low");
        service.handle(msg);

        ArgumentCaptor<AiJudgment> captor = ArgumentCaptor.forClass(AiJudgment.class);
        verify(aiJudgmentRepository).saveAndFlush(captor.capture());
        AiJudgment saved = captor.getValue();
        assertThat(saved.getExecutionStatus()).isEqualTo(AiExecutionStatus.RECOMMENDED);
        assertThat(saved.getStockTier()).isEqualTo(3);
        assertThat(saved.getMatchedRiskGrade()).isEqualTo(4); // ACTIVE
        assertThat(saved.getApprovalExpiresAt()).isNotNull();
        // 자동매매 실행 X — Order INSERT / Kafka 발행 안 됨
        verify(orderRepository, never()).saveAndFlush(any());
        verify(tradeOrderProducer, never()).publishOrderSubmitted(any());
    }

    @Test
    @DisplayName("isHolder=false + SELL → BLOCKED + 경고 (비보유자 SELL 불가)")
    void nonHolderSellBlocked() {
        AiDecisionMessage msg = nonHolderMessage("sell", 700_000L, 70_000.0, 65_000.0, "low");
        service.handle(msg);

        ArgumentCaptor<AiJudgment> captor = ArgumentCaptor.forClass(AiJudgment.class);
        verify(aiJudgmentRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getExecutionStatus()).isEqualTo(AiExecutionStatus.BLOCKED);
        assertThat(captor.getValue().getStockTier()).isNull();
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("isHolder=false + HOLD → BLOCKED + 경고 (비보유자에 trade 외 결정 없음)")
    void nonHolderHoldBlocked() {
        AiDecisionMessage msg = nonHolderMessage(null, null, null, null, "low");
        service.handle(msg);

        ArgumentCaptor<AiJudgment> captor = ArgumentCaptor.forClass(AiJudgment.class);
        verify(aiJudgmentRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getExecutionStatus()).isEqualTo(AiExecutionStatus.BLOCKED);
    }

    @Test
    @DisplayName("isHolder=false 면 자동매매 OFF 여도 RECOMMENDED — 사용자 동의 후 매수 흐름")
    void nonHolderBuyBypassesAutoTradeOff() {
        // 자동매매 INACTIVE 로 덮어쓰기
        AutoTradeSettings off = AutoTradeSettings.builder()
                .userId(USER_ID).autoTradeStatus(AutoTradeStatus.INACTIVE).build();
        when(autoTradeSettingsRepository.findById(USER_ID)).thenReturn(Optional.of(off));
        when(stockRiskTierRedisRepository.get(STOCK)).thenReturn(2);
        when(investmentProfileRepository.findById(USER_ID))
                .thenReturn(Optional.of(buildProfileWithGrade("STABLE_SEEKING")));

        AiDecisionMessage msg = nonHolderMessage("buy", 500_000L, 50_000.0, 45_000.0, "low");
        service.handle(msg);

        ArgumentCaptor<AiJudgment> captor = ArgumentCaptor.forClass(AiJudgment.class);
        verify(aiJudgmentRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getExecutionStatus()).isEqualTo(AiExecutionStatus.RECOMMENDED);
    }

    @Test
    @DisplayName("isHolder=false + BUY, stock_tier Redis 미적재 → stockTier null, 그래도 RECOMMENDED 진행")
    void nonHolderBuyMissingStockTierStillRecommended() {
        when(stockRiskTierRedisRepository.get(STOCK)).thenReturn(null);
        when(investmentProfileRepository.findById(USER_ID))
                .thenReturn(Optional.of(buildProfileWithGrade("AGGRESSIVE")));

        AiDecisionMessage msg = nonHolderMessage("buy", 700_000L, 70_000.0, 65_000.0, "low");
        service.handle(msg);

        ArgumentCaptor<AiJudgment> captor = ArgumentCaptor.forClass(AiJudgment.class);
        verify(aiJudgmentRepository).saveAndFlush(captor.capture());
        AiJudgment saved = captor.getValue();
        assertThat(saved.getExecutionStatus()).isEqualTo(AiExecutionStatus.RECOMMENDED);
        assertThat(saved.getStockTier()).isNull();
        assertThat(saved.getMatchedRiskGrade()).isEqualTo(5); // AGGRESSIVE
    }

    @Test
    @DisplayName("isHolder=false + BUY, profile row 없음 → matchedRiskGrade null")
    void nonHolderBuyMissingProfileGradeNull() {
        when(stockRiskTierRedisRepository.get(STOCK)).thenReturn(3);
        when(investmentProfileRepository.findById(USER_ID)).thenReturn(Optional.empty());

        AiDecisionMessage msg = nonHolderMessage("buy", 700_000L, 70_000.0, 65_000.0, "low");
        service.handle(msg);

        ArgumentCaptor<AiJudgment> captor = ArgumentCaptor.forClass(AiJudgment.class);
        verify(aiJudgmentRepository).saveAndFlush(captor.capture());
        AiJudgment saved = captor.getValue();
        assertThat(saved.getStockTier()).isEqualTo(3);
        assertThat(saved.getMatchedRiskGrade()).isNull();
    }

    @Test
    @DisplayName("isHolder=true 는 기존 보유자 흐름 그대로 (READY 진입)")
    void holderTrueFollowsLegacyFlow() {
        AiDecisionMessage msg = holderMessage(true, "buy", 700_000L, 70_000.0, 65_000.0, "low");
        service.handle(msg);

        ArgumentCaptor<AiJudgment> captor = ArgumentCaptor.forClass(AiJudgment.class);
        verify(aiJudgmentRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getExecutionStatus()).isEqualTo(AiExecutionStatus.READY);
        // RECOMMENDED 컨텍스트는 채우지 않음
        assertThat(captor.getValue().getStockTier()).isNull();
        assertThat(captor.getValue().getMatchedRiskGrade()).isNull();
        verify(orderRepository).saveAndFlush(any());
    }

    @Test
    @DisplayName("isHolder=null 도 보유자로 간주 — 하위 호환 (READY 진입)")
    void holderNullTreatedAsHolder() {
        AiDecisionMessage msg = holderMessage(null, "buy", 700_000L, 70_000.0, 65_000.0, "low");
        service.handle(msg);

        ArgumentCaptor<AiJudgment> captor = ArgumentCaptor.forClass(AiJudgment.class);
        verify(aiJudgmentRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getExecutionStatus()).isEqualTo(AiExecutionStatus.READY);
    }

    // ───────────────────────────────────────────────────────────────────
    // 헬퍼
    // ───────────────────────────────────────────────────────────────────

    private AiDecisionMessage nonHolderMessage(
            String side, Long orderAmount, Double targetPrice, Double stopLossPrice, String riskLevel
    ) {
        return holderMessage(false, side, orderAmount, targetPrice, stopLossPrice, riskLevel);
    }

    private AiDecisionMessage holderMessage(
            Boolean isHolder, String side, Long orderAmount,
            Double targetPrice, Double stopLossPrice, String riskLevel
    ) {
        String action = side == null ? "hold" : "trade";
        AiDecisionMessage.FinalDecision fd = "hold".equals(action)
                ? new AiDecisionMessage.FinalDecision("hold", null, null, null, null, "보류", 0.5, riskLevel)
                : new AiDecisionMessage.FinalDecision(
                        action, side, orderAmount, targetPrice, stopLossPrice,
                        "AI 판단 사유", 0.78, riskLevel
                );
        return new AiDecisionMessage(
                USER_ID, EVENT_ID, STOCK,
                OffsetDateTime.parse("2026-05-14T02:07:00+09:00"),
                null,
                fd,
                new AiDecisionMessage.Debate("Bull", "Bear", "bull", objectMapper.createArrayNode()),
                objectMapper.createObjectNode(),
                "completed",
                isHolder
        );
    }

    private InvestmentProfile buildProfileWithGrade(String riskGrade) {
        return InvestmentProfile.builder()
                .userId(USER_ID)
                .riskScore(50L)
                .riskGrade(riskGrade)
                .answersSnapshot(java.util.Map.of())
                .build();
    }

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
                fd, debate, indicators, flowStatus, null
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
