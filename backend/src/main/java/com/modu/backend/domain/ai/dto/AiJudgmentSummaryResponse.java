package com.modu.backend.domain.ai.dto;

import com.modu.backend.domain.ai.entity.AiJudgment;

import java.time.OffsetDateTime;

public record AiJudgmentSummaryResponse(
        Long judgmentId,
        Long orderId,
        String stockCode,
        String eventType,
        Long confidenceScore,
        String judgmentReason,
        OffsetDateTime judgedAt
) {

    public static AiJudgmentSummaryResponse from(AiJudgment judgment) {
        return new AiJudgmentSummaryResponse(
                judgment.getId(),
                judgment.getOrderId(),
                judgment.getStockCode(),
                judgment.getDecision(),
                judgment.getConfidenceScore(),
                judgment.getJudgmentReason(),
                judgment.getJudgedAt()
        );
    }
}
