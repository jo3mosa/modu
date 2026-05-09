package com.modu.backend.domain.ai.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.modu.backend.domain.ai.entity.AiJudgment;

import java.time.OffsetDateTime;

public record AiJudgmentDetailResponse(
        Long judgmentId,
        Long orderId,
        String stockCode,
        String eventType,
        Long confidenceScore,
        JsonNode indicatorsSnapshot,
        String judgmentReason,
        OffsetDateTime judgedAt
) {

    public static AiJudgmentDetailResponse from(AiJudgment judgment) {
        return new AiJudgmentDetailResponse(
                judgment.getId(),
                judgment.getOrderId(),
                judgment.getStockCode(),
                judgment.getDecision(),
                judgment.getConfidenceScore(),
                judgment.getIndicatorsSnapshot(),
                judgment.getJudgmentReason(),
                judgment.getJudgedAt()
        );
    }
}
