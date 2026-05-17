package com.modu.backend.domain.ai.dto;

import com.modu.backend.domain.ai.entity.AiJudgment;

import java.time.OffsetDateTime;

/**
 * 승인 대기 AI 판단 목록 항목 (S14P31B106-292)
 *
 * GET /api/v1/ai-agent/decisions/pending 응답 항목
 */
public record PendingDecisionResponse(
        Long id,
        String stockCode,
        String decision,
        Long orderAmount,
        Long targetPrice,
        Long stopLossPrice,
        String reasonSummary,
        String riskLevel,
        Long confidenceScore,
        OffsetDateTime judgedAt,
        OffsetDateTime approvalExpiresAt
) {
    public static PendingDecisionResponse from(AiJudgment j) {
        return new PendingDecisionResponse(
                j.getId(),
                j.getStockCode(),
                j.getDecision(),
                j.getOrderAmount(),
                j.getTargetPrice(),
                j.getStopLossPrice(),
                j.getJudgmentReason(),
                j.getRiskGrade(),
                j.getConfidenceScore(),
                j.getJudgedAt(),
                j.getApprovalExpiresAt()
        );
    }
}
