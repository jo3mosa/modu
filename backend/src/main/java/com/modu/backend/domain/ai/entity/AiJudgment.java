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
            OffsetDateTime judgedAt
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
    }
}
