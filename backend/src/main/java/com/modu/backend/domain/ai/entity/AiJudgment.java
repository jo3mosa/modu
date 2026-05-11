package com.modu.backend.domain.ai.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * AI 판단 결과 엔티티
 *
 * [저장 시점]
 * ai.decision.generated 토픽 수신 시 AiDecisionConsumer → SignalHandlerService에서 INSERT
 *
 * [decision 값]
 * - BUY  : AI가 매수 결정 → SignalHandlerService가 trade.order.submitted 발행
 * - SELL : AI가 매도 결정 → SignalHandlerService가 trade.order.submitted 발행
 * - HOLD : 관망 결정 → 주문 발행 없이 기록만
 *
 * [nullable 필드]
 * HOLD 결정이면 order_id, target_price, stop_loss_price, order_amount 모두 null
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

    // BUY/SELL 결정 시 생성된 orders.id, HOLD면 null
    @Column(name = "order_id")
    private Long orderId;

    // 판단 당시 적용된 매매 규칙 버전 → trading_rule_histories.id
    @Column(name = "rule_history_id")
    private Long ruleHistoryId;

    // BUY / SELL / HOLD
    @Column(name = "decision", nullable = false, length = 20)
    private String decision;

    // AI 신뢰도: float 0~1 → int 0~100 변환 후 저장
    @Column(name = "confidence_score", nullable = false)
    private Long confidenceScore;

    // 판단 시점의 기술/펀더멘털 지표 스냅샷 (JSONB)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "indicators_snapshot", nullable = false, columnDefinition = "jsonb")
    private JsonNode indicatorsSnapshot;

    // AI가 생성한 판단 사유 요약
    @Column(name = "judgment_reason", nullable = false)
    private String judgmentReason;

    @Column(name = "judged_at", nullable = false)
    private OffsetDateTime judgedAt;

    // ── V20260509205300 마이그레이션으로 추가된 컬럼들 ─────────────────────

    // 판단에 사용된 주요 시그널 목록 (예: ["technical_signal", "sentiment_signal"])
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "key_signals", columnDefinition = "jsonb")
    private JsonNode keySignals;

    // Bull/Bear 애널리스트 주장 (토론 결과 저장용, nullable)
    @Column(name = "bull_claim")
    private String bullClaim;

    @Column(name = "bear_claim")
    private String bearClaim;

    // 토론 승자: bull / bear / balanced
    @Column(name = "winning_side", length = 20)
    private String winningSide;

    // HOLD면 null
    @Column(name = "target_price")
    private Long targetPrice;

    @Column(name = "stop_loss_price")
    private Long stopLossPrice;

    @Column(name = "order_amount")
    private Long orderAmount;

    @Column(name = "sector")
    private String sector;

    @Column(name = "risk_grade", length = 20)
    private String riskGrade;

    @Column(name = "expected_scenario", length = 20)
    private String expectedScenario;

    // BUY/SELL 결정 시 주문 생성 후 orderId를 역으로 채워넣기 위한 메서드
    public void updateOrderId(Long orderId) {
        this.orderId = orderId;
    }

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
            Long targetPrice,
            Long stopLossPrice,
            Long orderAmount,
            String winningSide,
            String sector,
            String riskGrade,
            String expectedScenario
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
        this.keySignals = keySignals;
        this.bullClaim = bullClaim;
        this.bearClaim = bearClaim;
        this.targetPrice = targetPrice;
        this.stopLossPrice = stopLossPrice;
        this.orderAmount = orderAmount;
        this.winningSide = winningSide;
        this.sector = sector;
        this.riskGrade = riskGrade;
        this.expectedScenario = expectedScenario;
    }
}
