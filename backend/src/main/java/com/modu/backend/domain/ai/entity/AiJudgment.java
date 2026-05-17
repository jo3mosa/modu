package com.modu.backend.domain.ai.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * AI 판단 결과 엔티티 (ai_judgments 테이블)
 *
 * 매핑 출처:
 *  - 기본 필드: V20260429... init 마이그레이션
 *  - 확장 필드 (key_signals 외 9개): V20260509205300 마이그레이션
 *  - decision_id / source_event_id / execution_status: V20260515103200 마이그레이션 (S14P31B106-263)
 *  - approval_expires_at: V20260515174500 마이그레이션 (S14P31B106-292)
 */
@Entity
@Table(name = "ai_judgments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiJudgment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "stock_code", nullable = false)
    private String stockCode;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "rule_history_id")
    private Long ruleHistoryId;

    @Column(name = "decision", nullable = false, length = 20)
    private String decision;

    @Column(name = "confidence_score", nullable = false)
    private Long confidenceScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "indicators_snapshot", nullable = false, columnDefinition = "jsonb")
    private JsonNode indicatorsSnapshot;

    @Column(name = "judgment_reason", nullable = false)
    private String judgmentReason;

    @Column(name = "judged_at", nullable = false)
    private OffsetDateTime judgedAt;

    // ─────────────────────────────────────────────────────────────────────
    // V20260509205300 확장 컬럼
    // ─────────────────────────────────────────────────────────────────────

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "key_signals", nullable = false, columnDefinition = "jsonb")
    private JsonNode keySignals;

    @Column(name = "bull_claim")
    private String bullClaim;

    @Column(name = "bear_claim")
    private String bearClaim;

    @Column(name = "sector")
    private String sector;

    @Column(name = "risk_grade", length = 20)
    private String riskGrade;

    @Column(name = "target_price")
    private Long targetPrice;

    @Column(name = "stop_loss_price")
    private Long stopLossPrice;

    @Column(name = "order_amount")
    private Long orderAmount;

    @Column(name = "winning_side", length = 20)
    private String winningSide;

    @Column(name = "expected_scenario", length = 20)
    private String expectedScenario;

    // ─────────────────────────────────────────────────────────────────────
    // V20260515103200 (S14P31B106-263) 추가 컬럼
    // ─────────────────────────────────────────────────────────────────────

    @Column(name = "decision_id")
    private String decisionId;

    @Column(name = "source_event_id")
    private String sourceEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_status", length = 20)
    private AiExecutionStatus executionStatus;

    // ─────────────────────────────────────────────────────────────────────
    // V20260515174500 (S14P31B106-292) 추가 컬럼
    // ─────────────────────────────────────────────────────────────────────

    @Column(name = "approval_expires_at")
    private OffsetDateTime approvalExpiresAt;

    @Builder
    public AiJudgment(
            Long userId,
            String stockCode,
            Long orderId,
            Long ruleHistoryId,
            String decision,
            Long confidenceScore,
            JsonNode indicatorsSnapshot,
            String judgmentReason,
            OffsetDateTime judgedAt,
            JsonNode keySignals,
            String bullClaim,
            String bearClaim,
            String sector,
            String riskGrade,
            Long targetPrice,
            Long stopLossPrice,
            Long orderAmount,
            String winningSide,
            String expectedScenario,
            String decisionId,
            String sourceEventId,
            AiExecutionStatus executionStatus
    ) {
        this.userId = userId;
        this.stockCode = stockCode;
        this.orderId = orderId;
        this.ruleHistoryId = ruleHistoryId;
        this.decision = decision;
        this.confidenceScore = confidenceScore;
        this.indicatorsSnapshot = indicatorsSnapshot;
        this.judgmentReason = judgmentReason;
        this.judgedAt = judgedAt;
        this.keySignals = Objects.requireNonNull(keySignals, "keySignals must not be null (DB NOT NULL DEFAULT '[]')");
        this.bullClaim = bullClaim;
        this.bearClaim = bearClaim;
        this.sector = sector;
        this.riskGrade = riskGrade;
        this.targetPrice = targetPrice;
        this.stopLossPrice = stopLossPrice;
        this.orderAmount = orderAmount;
        this.winningSide = winningSide;
        this.expectedScenario = expectedScenario;
        this.decisionId = decisionId;
        this.sourceEventId = sourceEventId;
        this.executionStatus = executionStatus;
    }

    /**
     * 체결 이후 우리 orders.id 를 채우는 도메인 메서드
     * 발행 시점에는 order_id 가 null, 후속 trade.order.executed 컨슈머가 호출 예정
     */
    public void linkOrder(Long orderId) {
        this.orderId = orderId;
    }

    /**
     * APPROVAL_REQUIRED 진입 시 만료 시각 설정 (S14P31B106-292)
     */
    public void setApprovalExpiresAt(OffsetDateTime expiresAt) {
        this.approvalExpiresAt = expiresAt;
    }

    /**
     * 사용자 승인 처리 — READY 로 전환 + order_id 연결 + 만료 시각 제거 (S14P31B106-292)
     */
    public void markApproved(Long orderId) {
        this.executionStatus = AiExecutionStatus.READY;
        this.orderId = orderId;
        this.approvalExpiresAt = null;
    }

    /**
     * 사용자 거부 처리 — REJECTED 로 전환 + 만료 시각 제거 (S14P31B106-292)
     */
    public void markRejected() {
        this.executionStatus = AiExecutionStatus.REJECTED;
        this.approvalExpiresAt = null;
    }

    /**
     * 만료 처리 — EXPIRED 로 전환 + 만료 시각 제거 (S14P31B106-292)
     * 스케줄러 (단계 9) 가 호출
     */
    public void markExpired() {
        this.executionStatus = AiExecutionStatus.EXPIRED;
        this.approvalExpiresAt = null;
    }
}
