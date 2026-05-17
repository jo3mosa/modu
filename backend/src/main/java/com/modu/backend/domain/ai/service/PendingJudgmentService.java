package com.modu.backend.domain.ai.service;

import com.modu.backend.domain.ai.dto.DecisionApprovalResponse;
import com.modu.backend.domain.ai.dto.PendingDecisionResponse;
import com.modu.backend.domain.ai.entity.AiExecutionStatus;
import com.modu.backend.domain.ai.entity.AiJudgment;
import com.modu.backend.domain.ai.exception.AiErrorCode;
import com.modu.backend.domain.ai.repository.AiJudgmentRepository;
import com.modu.backend.domain.trading.entity.Order;
import com.modu.backend.domain.trading.entity.OrderSide;
import com.modu.backend.domain.trading.entity.OrderSource;
import com.modu.backend.domain.trading.entity.OrderStatus;
import com.modu.backend.domain.trading.entity.OrderType;
import com.modu.backend.domain.trading.kafka.producer.TradeOrderProducer;
import com.modu.backend.domain.trading.position.repository.PositionThresholdRepository;
import com.modu.backend.domain.trading.repository.OrderRepository;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.kafka.dto.TradeOrderMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * AI 판단 승인/거부 처리 (S14P31B106-292)
 *
 * [엔드포인트]
 *  - GET  /api/v1/ai-agent/decisions/pending          — 승인 대기 목록
 *  - POST /api/v1/ai-agent/decisions/{id}/approve     — 승인 → READY + Order INSERT + Kafka 발행
 *  - POST /api/v1/ai-agent/decisions/{id}/reject      — 거부 → REJECTED
 *
 * [검증]
 *  - 본인 row 인지 (userId 매칭) → DECISION_FORBIDDEN
 *  - execution_status = APPROVAL_REQUIRED 인지 → DECISION_NOT_PENDING
 *  - approval_expires_at > NOW() 인지 → DECISION_EXPIRED
 *
 * [트랜잭션]
 *  approve/reject 메서드 단일 @Transactional. Kafka 발행은 commit 후 (afterCommit) — SignalHandlerService 동일 패턴.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PendingJudgmentService {

    private final AiJudgmentRepository aiJudgmentRepository;
    private final OrderRepository orderRepository;
    private final PositionThresholdRepository positionThresholdRepository;
    private final TradeOrderProducer tradeOrderProducer;

    // ───────────────────────────────────────────────────────────────────
    // 조회
    // ───────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PendingDecisionResponse> listPending(Long userId) {
        return aiJudgmentRepository
                .findByUserIdAndExecutionStatusOrderByJudgedAtDesc(userId, AiExecutionStatus.APPROVAL_REQUIRED)
                .stream()
                .filter(j -> j.getApprovalExpiresAt() == null
                        || j.getApprovalExpiresAt().isAfter(OffsetDateTime.now()))
                .map(PendingDecisionResponse::from)
                .toList();
    }

    // ───────────────────────────────────────────────────────────────────
    // 승인
    // ───────────────────────────────────────────────────────────────────

    @Transactional
    public DecisionApprovalResponse approve(Long userId, Long judgmentId) {
        // pessimistic write lock — 동일 judgmentId 동시 승인 race 차단
        AiJudgment judgment = loadForUpdateAndValidate(userId, judgmentId);

        // decision 명시 검증 — 데이터 이상값(HOLD 등) 이 SELL 로 강제 매핑되는 것 방지
        OrderSide side = resolveSide(judgment.getDecision());

        // 주문 파라미터 검증
        Long orderAmount = judgment.getOrderAmount();
        Long limitPrice = judgment.getTargetPrice();
        if (orderAmount == null || limitPrice == null || limitPrice <= 0) {
            throw new ApiException(AiErrorCode.INVALID_ORDER_PARAMS);
        }
        long quantity = orderAmount / limitPrice;
        if (quantity <= 0) {
            throw new ApiException(AiErrorCode.INVALID_ORDER_PARAMS);
        }

        String idempotencyKey = UUID.randomUUID().toString();

        Order order = Order.builder()
                .userId(userId)
                .stockCode(judgment.getStockCode())
                .side(side)
                .orderType(OrderType.LIMIT)
                .quantity(quantity)
                .limitPrice(limitPrice)
                .status(OrderStatus.PENDING)
                .source(OrderSource.AI_DECISION)
                .idempotencyKey(idempotencyKey)
                .build();
        orderRepository.saveAndFlush(order);

        judgment.markApproved(order.getId());

        // BUY 시 PositionThreshold AI 임계 갱신 (활성 row 있을 때만)
        if (side == OrderSide.BUY) {
            positionThresholdRepository
                    .findByUserIdAndStockCodeAndIsActiveTrue(userId, judgment.getStockCode())
                    .ifPresent(p -> p.updateAiThresholds(judgment.getTargetPrice(), judgment.getStopLossPrice()));
        }

        TradeOrderMessage payload = TradeOrderMessage.of(
                idempotencyKey, null,
                userId, judgment.getStockCode(),
                side, OrderType.LIMIT,
                quantity, limitPrice,
                OrderSource.AI_DECISION, null,
                OffsetDateTime.now()
        );
        registerAfterCommitPublish(payload, order.getId());

        log.info("AI 판단 승인 - userId: {}, judgmentId: {}, orderId: {}, side: {}, qty: {}, price: {}",
                userId, judgmentId, order.getId(), side, quantity, limitPrice);

        return DecisionApprovalResponse.from(judgment);
    }

    // ───────────────────────────────────────────────────────────────────
    // 거부
    // ───────────────────────────────────────────────────────────────────

    @Transactional
    public DecisionApprovalResponse reject(Long userId, Long judgmentId) {
        // pessimistic write lock — 승인/거부 동시 처리 race 차단
        AiJudgment judgment = loadForUpdateAndValidate(userId, judgmentId);
        judgment.markRejected();
        log.info("AI 판단 거부 - userId: {}, judgmentId: {}", userId, judgmentId);
        return DecisionApprovalResponse.from(judgment);
    }

    // ───────────────────────────────────────────────────────────────────
    // 만료 처리 (S14P31B106-292)
    // ───────────────────────────────────────────────────────────────────

    /**
     * 만료 후보 일괄 EXPIRED 전환 — 스케줄러가 1분 간격 호출.
     *
     * 한 트랜잭션 안에서 List 처리 — 일관성 보장.
     * 단일 인스턴스 가정. 다중 인스턴스 시 동일 row 를 여러 노드가 UPDATE 할 수 있으나 markExpired 가
     * idempotent (enum 변경) 이라 큰 위험 X. 추후 분산 락 도입은 별도 이슈.
     *
     * @return 만료 처리한 row 개수
     */
    @Transactional
    public int expirePending() {
        OffsetDateTime threshold = OffsetDateTime.now();
        List<AiJudgment> expired = aiJudgmentRepository
                .findByExecutionStatusAndApprovalExpiresAtBefore(AiExecutionStatus.APPROVAL_REQUIRED, threshold);
        if (expired.isEmpty()) return 0;
        expired.forEach(AiJudgment::markExpired);
        log.info("AI 판단 승인 만료 처리 - count: {}, threshold: {}", expired.size(), threshold);
        return expired.size();
    }

    // ───────────────────────────────────────────────────────────────────
    // 공통 검증
    // ───────────────────────────────────────────────────────────────────

    /**
     * pessimistic row lock 으로 조회 + 검증
     * 동일 judgmentId 에 대한 동시 승인/거부 race 차단 — 두 번째 요청은 락 대기 후
     * 첫 요청이 완료된 row 상태(READY/REJECTED) 를 보고 DECISION_NOT_PENDING 으로 거절됨.
     */
    private AiJudgment loadForUpdateAndValidate(Long userId, Long judgmentId) {
        AiJudgment judgment = aiJudgmentRepository.findByIdForUpdate(judgmentId)
                .orElseThrow(() -> new ApiException(AiErrorCode.JUDGMENT_NOT_FOUND));

        if (!judgment.getUserId().equals(userId)) {
            throw new ApiException(AiErrorCode.DECISION_FORBIDDEN);
        }
        if (judgment.getExecutionStatus() != AiExecutionStatus.APPROVAL_REQUIRED) {
            throw new ApiException(AiErrorCode.DECISION_NOT_PENDING);
        }
        if (judgment.getApprovalExpiresAt() != null
                && !judgment.getApprovalExpiresAt().isAfter(OffsetDateTime.now())) {
            throw new ApiException(AiErrorCode.DECISION_EXPIRED);
        }
        return judgment;
    }

    /**
     * decision 명시 검증 — BUY/SELL 만 허용. HOLD 등 이상값은 INVALID_ORDER_PARAMS 로 거절.
     * APPROVAL_REQUIRED row 는 의미상 BUY/SELL 만이나 데이터 이상값에 대한 fail-fast 방어.
     */
    private OrderSide resolveSide(String decision) {
        if ("BUY".equalsIgnoreCase(decision)) return OrderSide.BUY;
        if ("SELL".equalsIgnoreCase(decision)) return OrderSide.SELL;
        throw new ApiException(AiErrorCode.INVALID_ORDER_PARAMS);
    }

    // ───────────────────────────────────────────────────────────────────
    // Kafka 발행 — After Commit (SignalHandlerService 동일 패턴)
    // ───────────────────────────────────────────────────────────────────

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
            log.error("AI 승인 주문 Kafka 발행 실패 (커밋 후, orphan 위험) - orderId: {}", orderId, e);
        }
    }
}
