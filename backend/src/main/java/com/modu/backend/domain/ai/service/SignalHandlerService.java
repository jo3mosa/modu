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
import com.modu.backend.domain.strategy.dto.InvestmentRiskLevel;
import com.modu.backend.domain.strategy.entity.AutoTradeStatus;
import com.modu.backend.domain.strategy.repository.AutoTradeSettingsRepository;
import com.modu.backend.domain.trading.entity.Order;
import com.modu.backend.domain.trading.entity.OrderSide;
import com.modu.backend.domain.trading.entity.OrderSource;
import com.modu.backend.domain.trading.entity.OrderStatus;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * AI 판단 메시지 처리 본체 (S14P31B106-263)
 *
 * [처리 흐름]
 *  1. 메시지 필수 필드 검증
 *  2. flow_status / action 기반 execution_status 결정
 *  3. decision (BUY/SELL/HOLD) 매핑
 *  4. READY 케이스: Order INSERT (PENDING) → trade.order.submitted 발행 → BUY 면 PositionThreshold 갱신
 *  5. AiJudgment INSERT (중복 시 silent skip — partial unique index 가 1차 방어선)
 *
 * [트랜잭션]
 *  메서드 전체 @Transactional. Order/AiJudgment INSERT 와 PositionThreshold 갱신은 같은 tx 에서 커밋.
 *
 *  Kafka 발행은 TransactionSynchronization.afterCommit 으로 분리 — DB 커밋 후 발행.
 *  이유: tx 안에서 발행하면 Consumer (KisOrderConsumer) 가 미커밋 row 를 SELECT 못 해 "주문 row 없음"
 *  으로 메시지를 폐기하는 race condition 발생.
 *
 *  단점: 발행 실패 시 Order 만 PENDING 으로 stuck (orphan). 진짜 운영급 원자성은 Transactional
 *  Outbox 패턴 필요 — 별도 이슈로 분리.
 *
 * [멱등성]
 *  1차 방어선: handle() 진입 직후 existsByUserIdAndSourceEventId 사전 SELECT.
 *  2차 방어선: ai_judgments (user_id, source_event_id) partial unique index.
 *
 *  사전 SELECT 가 정상 경로에서 중복을 막음. DB unique violation 까지 도달하면 @Transactional 이
 *  rollback-only 마크되어 catch 해도 UnexpectedRollbackException 발생 → 재시도 후 SELECT 통과로 회수.
 *  Kafka 파티션 키 = user_id 라 같은 user 메시지는 같은 컨슈머가 순차 처리 → race 거의 없음.
 *
 * [실패 정책]
 *  - 비즈니스 예외: ApiException 던짐 → Consumer 가 ack
 *  - 시스템 예외: 그대로 전파 → Consumer 가 미커밋 → 재시도
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignalHandlerService {

    private final OrderRepository orderRepository;
    private final AiJudgmentRepository aiJudgmentRepository;
    private final TradingRuleRepository tradingRuleRepository;
    private final PositionThresholdRepository positionThresholdRepository;
    private final AutoTradeSettingsRepository autoTradeSettingsRepository;
    private final PortfolioCheckService portfolioCheckService;
    private final TradeOrderProducer tradeOrderProducer;
    private final ObjectMapper objectMapper;
    // S14P31B106-354 — RECOMMENDED 분기 컨텍스트 조회 (BE 자체 조달)
    private final StockRiskTierRedisRepository stockRiskTierRedisRepository;
    private final InvestmentProfileRepository investmentProfileRepository;

    @Transactional
    public void handle(AiDecisionMessage message) {
        validate(message);

        // 1. 사전 멱등 체크 — DB unique 위반까지 가지 않게 정상 경로 차단
        if (aiJudgmentRepository.existsByUserIdAndSourceEventId(message.userId(), message.sourceEventId())) {
            log.info("AI 판단 중복 메시지 무시 - userId: {}, sourceEventId: {}",
                    message.userId(), message.sourceEventId());
            return;
        }

        AiExecutionStatus status = resolveExecutionStatus(message);
        String decision = resolveDecision(message);

        // 2. AiJudgment INSERT (race 발생 시 unique violation → 시스템 예외 → 재시도 시 위 SELECT 가 회수)
        AiJudgment judgment = insertJudgment(message, status, decision);

        // 3. READY 케이스에만 Order INSERT + Kafka 발행
        if (status == AiExecutionStatus.READY) {
            Long orderId = publishOrder(message, decision);
            judgment.linkOrder(orderId);
            if ("BUY".equals(decision)) {
                updateAiThresholds(message);
            }
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // 검증
    // ───────────────────────────────────────────────────────────────────

    private void validate(AiDecisionMessage m) {
        // 명세상 필수 필드 (AiDecisionMessage JavaDoc 참조)
        if (m == null
                || m.userId() == null
                || isBlank(m.stockCode())
                || isBlank(m.sourceEventId())
                || m.finalDecision() == null
                || isBlank(m.flowStatus())) {
            throw new ApiException(AiErrorCode.INVALID_DECISION_MESSAGE);
        }
        if ("running".equalsIgnoreCase(m.flowStatus())) {
            throw new ApiException(AiErrorCode.UNSUPPORTED_FLOW_STATUS);
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // execution_status 결정
    // ───────────────────────────────────────────────────────────────────

    private AiExecutionStatus resolveExecutionStatus(AiDecisionMessage m) {
        // 0-A. 비보유자 분기 (S14P31B106-354) — 자동매매 상태와 무관 (사용자 동의 후 수동 매수 성격)
        //      isHolder=null/true 는 보유자 간주 → 기존 흐름. false 는 BUY 만 RECOMMENDED.
        if (Boolean.FALSE.equals(m.isHolder())) {
            return resolveNonHolderStatus(m);
        }

        // 0-B. 자동매매 상태 확인 — ACTIVE 아니면 BLOCKED (사용자 OFF / Kill Switch / row 없음 모두 차단)
        //      AI 가 Redis 보고 사전 필터링하나 race 가드 차원에서 BE 도 한 번 더 확인
        if (!isAutoTradeActive(m.userId())) {
            return AiExecutionStatus.BLOCKED;
        }

        String flow = m.flowStatus();

        if ("blocked".equalsIgnoreCase(flow) || "failed".equalsIgnoreCase(flow)) {
            return AiExecutionStatus.BLOCKED;
        }
        if ("hold".equalsIgnoreCase(flow)) {
            return AiExecutionStatus.HOLD_ONLY;
        }

        // completed 기대
        AiDecisionMessage.FinalDecision fd = m.finalDecision();
        if (fd == null || "hold".equalsIgnoreCase(fd.action())) {
            return AiExecutionStatus.HOLD_ONLY;
        }
        if (!"trade".equalsIgnoreCase(fd.action())) {
            throw new ApiException(AiErrorCode.INVALID_DECISION_MESSAGE);
        }

        String side = fd.side();
        if (!"buy".equalsIgnoreCase(side) && !"sell".equalsIgnoreCase(side)) {
            throw new ApiException(AiErrorCode.INVALID_DECISION_MESSAGE);
        }

        // APPROVAL_REQUIRED 판정
        if ("high".equalsIgnoreCase(fd.riskLevel())) {
            return AiExecutionStatus.APPROVAL_REQUIRED;
        }

        // BUY 한정 — AI 운용 한도 hard rule (단일 + 누적). 사용자 한도 초과는 협상 불가 → BLOCKED.
        // AI risk_gate 가 단일 주문은 1차 검증하지만 누적은 실시간 데이터가 필요해 BE 영역 (이중 게이트).
        // exceedsDailyBuyLimit (APPROVAL_REQUIRED) 보다 앞에 두어 hard rule 이 먼저 판정되도록 함.
        if ("buy".equalsIgnoreCase(side) && exceedsAiBudget(m.userId(), nullToZero(fd.orderAmount()))) {
            return AiExecutionStatus.BLOCKED;
        }

        // BUY 한정 일일 한도 초과 체크 (sumTodayBuyAmount 는 BUY 만 합산)
        if ("buy".equalsIgnoreCase(side) && exceedsDailyBuyLimit(m.userId(), nullToZero(fd.orderAmount()))) {
            return AiExecutionStatus.APPROVAL_REQUIRED;
        }

        // 사전 잔고/보유 검증 (READY 진입 직전) — 실패 시 BLOCKED
        if (!hasSufficientResource(m, side, fd)) {
            return AiExecutionStatus.BLOCKED;
        }

        return AiExecutionStatus.READY;
    }

    /**
     * 비보유자 분기 (S14P31B106-354):
     *  - flow_status 가 "completed" 가 아니면 BLOCKED (failed / blocked / hold / running 등)
     *  - final_decision 없음 / trade 아님 → BLOCKED + 경고
     *  - SELL/HOLD → BLOCKED + 경고 로그 (비보유자에게 발행돼서는 안 되는 케이스)
     *  - BUY → RECOMMENDED (사용자 승인 시 매수 실행)
     */
    private AiExecutionStatus resolveNonHolderStatus(AiDecisionMessage m) {
        if (!"completed".equalsIgnoreCase(m.flowStatus())) {
            log.warn("[RECOMMENDED] 비보유자 비완료 flow_status 차단 - userId: {}, stockCode: {}, flowStatus: {}",
                    m.userId(), m.stockCode(), m.flowStatus());
            return AiExecutionStatus.BLOCKED;
        }
        AiDecisionMessage.FinalDecision fd = m.finalDecision();
        if (fd == null || !"trade".equalsIgnoreCase(fd.action())) {
            log.warn("[RECOMMENDED] 비보유자에게 trade 아닌 결정 수신 - userId: {}, stockCode: {}, action: {}",
                    m.userId(), m.stockCode(), fd != null ? fd.action() : null);
            return AiExecutionStatus.BLOCKED;
        }
        if (!"buy".equalsIgnoreCase(fd.side())) {
            log.warn("[RECOMMENDED] 비보유자에게 BUY 아닌 side 수신 - userId: {}, stockCode: {}, side: {}",
                    m.userId(), m.stockCode(), fd.side());
            return AiExecutionStatus.BLOCKED;
        }
        return AiExecutionStatus.RECOMMENDED;
    }

    /**
     * 자동매매 상태 = ACTIVE 인지 확인. AutoTradeSettings row 없으면 false (신규 사용자 보호)
     */
    private boolean isAutoTradeActive(Long userId) {
        return autoTradeSettingsRepository.findById(userId)
                .map(s -> s.getAutoTradeStatus() == AutoTradeStatus.ACTIVE)
                .orElse(false);
    }

    /**
     * 사전 잔고/보유 검증 — Redis snapshot 또는 KIS fallback
     *  BUY  : cash_balance >= order_amount
     *  SELL : holdings 의 stock_code quantity >= (order_amount / target_price)
     *
     * 자료 조회 실패 시 PortfolioCheckService 가 true 반환 (KIS placeOrder 단계 최종 검증에 위임).
     *
     * [publishOrder 검증과 일치]
     * publishOrder 가 INVALID_ORDER_PARAMS 로 거절하는 케이스 (orderAmount/targetPrice/quantity ≤ 0)는
     * 여기서도 false 로 끊어 BLOCKED 분기시킴 — 그렇지 않으면 READY 진입 후 publishOrder 가 ApiException
     * 던져 tx 롤백 + 메시지 재시도 무한 루프 가능.
     */
    private boolean hasSufficientResource(AiDecisionMessage m, String side, AiDecisionMessage.FinalDecision fd) {
        Long orderAmount = fd.orderAmount();
        if (orderAmount == null || orderAmount <= 0) {
            return false;
        }
        if ("buy".equalsIgnoreCase(side)) {
            return portfolioCheckService.hasSufficientCash(m.userId(), orderAmount);
        }
        // SELL — publishOrder 의 quantity 계산식과 동일 (orderAmount / round(targetPrice))
        if (fd.targetPrice() == null || fd.targetPrice() <= 0) {
            return false;
        }
        long limitPrice = Math.round(fd.targetPrice());
        if (limitPrice <= 0) return false;
        long quantity = orderAmount / limitPrice;
        if (quantity <= 0) return false;
        return portfolioCheckService.hasSufficientHolding(m.userId(), m.stockCode(), quantity);
    }

    private boolean exceedsDailyBuyLimit(Long userId, long orderAmount) {
        TradingRule rule = tradingRuleRepository.findById(userId).orElse(null);
        if (rule == null) return false;
        long todayTotal = orderRepository.sumTodayBuyAmount(userId);
        return todayTotal + orderAmount > rule.getDailyLossLimitAmount();
    }

    /**
     * AI 운용 한도 — 단일 주문 + 오늘 누적 매수액이 사용자 설정 한도를 초과하는지.
     * AI risk_gate 가 결정 시점 스냅샷으로 단일 주문 1차 검증, 여기서는 실시간 누적까지 2차 검증한다 (이중 게이트).
     * 위반 시 hard rule 차단 — APPROVAL_REQUIRED 가 아니라 BLOCKED 로 매핑 (resolveExecutionStatus 참조).
     * rule row 없거나 미설정(0) → 검증 skip (AI risk_gate 와 동일 정책).
     */
    private boolean exceedsAiBudget(Long userId, long orderAmount) {
        TradingRule rule = tradingRuleRepository.findById(userId).orElse(null);
        if (rule == null || rule.getAiBudgetAmount() == null || rule.getAiBudgetAmount() <= 0) {
            return false;
        }
        long todayTotal = orderRepository.sumTodayBuyAmount(userId);
        return todayTotal + orderAmount > rule.getAiBudgetAmount();
    }

    // ───────────────────────────────────────────────────────────────────
    // decision 매핑
    // ───────────────────────────────────────────────────────────────────

    private String resolveDecision(AiDecisionMessage m) {
        AiDecisionMessage.FinalDecision fd = m.finalDecision();
        if (fd == null || "hold".equalsIgnoreCase(fd.action())) return "HOLD";
        if ("buy".equalsIgnoreCase(fd.side())) return "BUY";
        if ("sell".equalsIgnoreCase(fd.side())) return "SELL";
        return "HOLD";
    }

    // ───────────────────────────────────────────────────────────────────
    // Order INSERT + Kafka 발행 (READY 케이스)
    // ───────────────────────────────────────────────────────────────────

    private Long publishOrder(AiDecisionMessage m, String decision) {
        AiDecisionMessage.FinalDecision fd = m.finalDecision();
        if (fd.targetPrice() == null || fd.orderAmount() == null) {
            throw new ApiException(AiErrorCode.INVALID_ORDER_PARAMS);
        }

        long limitPrice = Math.round(fd.targetPrice());
        if (limitPrice <= 0) {
            throw new ApiException(AiErrorCode.INVALID_ORDER_PARAMS);
        }
        long quantity = fd.orderAmount() / limitPrice;
        if (quantity <= 0) {
            throw new ApiException(AiErrorCode.INVALID_ORDER_PARAMS);
        }

        OrderSide side = "BUY".equals(decision) ? OrderSide.BUY : OrderSide.SELL;
        String idempotencyKey = UUID.randomUUID().toString();

        Order order = Order.builder()
                .userId(m.userId())
                .stockCode(m.stockCode())
                .side(side)
                .orderType(OrderType.LIMIT)
                .quantity(quantity)
                .limitPrice(limitPrice)
                .status(OrderStatus.PENDING)
                .source(OrderSource.AI_DECISION)
                .idempotencyKey(idempotencyKey)
                .build();
        orderRepository.saveAndFlush(order);

        TradeOrderMessage payload = TradeOrderMessage.of(
                idempotencyKey,
                null,
                m.userId(),
                m.stockCode(),
                side,
                OrderType.LIMIT,
                quantity,
                limitPrice,
                OrderSource.AI_DECISION,
                null,
                OffsetDateTime.now()
        );
        registerAfterCommitPublish(payload, order.getId());

        log.info("AI 주문 INSERT 완료 (Kafka 발행은 commit 후) - userId: {}, stockCode: {}, side: {}, orderId: {}, qty: {}, price: {}",
                m.userId(), m.stockCode(), side, order.getId(), quantity, limitPrice);

        return order.getId();
    }

    /**
     * Kafka 발행을 트랜잭션 커밋 이후로 미루기 — Consumer (KisOrderConsumer) 가 커밋된 Order row 를
     * SELECT 할 수 있도록 보장. 단 커밋 후 발행 실패 시 Order 만 PENDING 으로 남는 orphan 위험은 Outbox 도입까지 잔재.
     *
     * 트랜잭션 sync 가 비활성인 컨텍스트 (단위 테스트 등) 에선 즉시 발행으로 폴백.
     */
    private void registerAfterCommitPublish(TradeOrderMessage payload, Long orderId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publishOrderMessage(payload, orderId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publishOrderMessage(payload, orderId);
            }
        });
    }

    private void publishOrderMessage(TradeOrderMessage payload, Long orderId) {
        try {
            tradeOrderProducer.publishOrderSubmitted(payload);
        } catch (Exception e) {
            log.error("AI 주문 Kafka 발행 실패 (커밋 후, orphan 위험) - orderId: {}", orderId, e);
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // PositionThreshold AI 임계가 갱신 (BUY READY 케이스)
    // ───────────────────────────────────────────────────────────────────

    private void updateAiThresholds(AiDecisionMessage m) {
        AiDecisionMessage.FinalDecision fd = m.finalDecision();
        Long aiTarget = fd.targetPrice() != null ? Math.round(fd.targetPrice()) : null;
        Long aiStop = fd.stopLossPrice() != null ? Math.round(fd.stopLossPrice()) : null;

        positionThresholdRepository
                .findByUserIdAndStockCodeAndIsActiveTrue(m.userId(), m.stockCode())
                .ifPresent(p -> p.updateAiThresholds(aiTarget, aiStop));
        // 활성 row 없으면 무시 — 체결 핸들러(291) 가 매수 체결 시 새 row INSERT 책임
    }

    // ───────────────────────────────────────────────────────────────────
    // AiJudgment INSERT (멱등 처리)
    // ───────────────────────────────────────────────────────────────────

    /**
     * AiJudgment INSERT. 정상 경로에선 사전 SELECT 가 중복을 차단해 이 단계에 도달.
     * race 로 unique violation 발생 시 그대로 예외 전파 → Consumer 미커밋 → 재시도 시 사전 SELECT 회수.
     */
    private AiJudgment insertJudgment(AiDecisionMessage m, AiExecutionStatus status, String decision) {
        AiDecisionMessage.FinalDecision fd = m.finalDecision();
        AiDecisionMessage.Debate debate = m.debate();

        // S14P31B106-354 — RECOMMENDED 만 tier/grade 컨텍스트 채움 (그 외는 null)
        Integer stockTier = null;
        Integer matchedRiskGrade = null;
        if (status == AiExecutionStatus.RECOMMENDED) {
            stockTier = stockRiskTierRedisRepository.get(m.stockCode());
            matchedRiskGrade = lookupUserGradeInt(m.userId());
        }

        AiJudgment judgment = AiJudgment.builder()
                .userId(m.userId())
                .stockCode(m.stockCode())
                .orderId(null)
                .decision(decision)
                .confidenceScore(toConfidenceScore(fd))
                .indicatorsSnapshot(nonNullJson(m.indicatorsSnapshot()))
                .judgmentReason(nonNullReason(fd))
                .judgedAt(m.createdAt() != null ? m.createdAt() : OffsetDateTime.now())
                // V20260509205300 확장
                .keySignals(nonNullJson(debate != null ? debate.keySignals() : null))
                .bullClaim(debate != null ? debate.bullClaim() : null)
                .bearClaim(debate != null ? debate.bearClaim() : null)
                .riskGrade(fd != null ? fd.riskLevel() : null)
                .targetPrice(fd != null && fd.targetPrice() != null ? Math.round(fd.targetPrice()) : null)
                .stopLossPrice(fd != null && fd.stopLossPrice() != null ? Math.round(fd.stopLossPrice()) : null)
                .orderAmount(fd != null ? fd.orderAmount() : null)
                .winningSide(debate != null ? debate.winner() : null)
                // V20260515103200 (263)
                .decisionId(m.decisionId())
                .sourceEventId(m.sourceEventId())
                .executionStatus(status)
                // V20260519220100 (354) — RECOMMENDED 컨텍스트
                .stockTier(stockTier)
                .matchedRiskGrade(matchedRiskGrade)
                .build();

        // APPROVAL_REQUIRED / RECOMMENDED 진입 시 5분 만료 시각 세팅
        // (RECOMMENDED 는 354 — 동일 sweeper 가 만료 처리)
        if (status == AiExecutionStatus.APPROVAL_REQUIRED || status == AiExecutionStatus.RECOMMENDED) {
            judgment.setApprovalExpiresAt(OffsetDateTime.now().plusMinutes(5));
        }

        aiJudgmentRepository.saveAndFlush(judgment);
        return judgment;
    }

    /**
     * 사용자 risk_grade 를 1~5 정수로 변환 (S14P31B106-354).
     * profile row 없거나 알 수 없는 grade 면 null 반환 (FE 멘트 풍부화는 best-effort).
     */
    private Integer lookupUserGradeInt(Long userId) {
        InvestmentProfile profile = investmentProfileRepository.findById(userId).orElse(null);
        if (profile == null || profile.getRiskGrade() == null) return null;
        try {
            return InvestmentRiskLevel.valueOf(profile.getRiskGrade()).toGradeInt();
        } catch (IllegalArgumentException e) {
            log.warn("[RECOMMENDED] 알 수 없는 riskGrade - userId: {}, value: {}",
                    userId, profile.getRiskGrade());
            return null;
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // 헬퍼
    // ───────────────────────────────────────────────────────────────────

    private long toConfidenceScore(AiDecisionMessage.FinalDecision fd) {
        if (fd == null || fd.confidence() == null) return 0L;
        long score = Math.round(fd.confidence() * 100);
        return Math.max(0L, Math.min(100L, score));
    }

    private JsonNode nonNullJson(JsonNode node) {
        return node != null ? node : objectMapper.createObjectNode();
    }

    private String nonNullReason(AiDecisionMessage.FinalDecision fd) {
        if (fd == null || fd.reasonSummary() == null) return "";
        return fd.reasonSummary();
    }

    private long nullToZero(Long v) {
        return v == null ? 0L : v;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
