package com.modu.backend.domain.ai.dto;

import com.modu.backend.domain.ai.entity.AiExecutionStatus;
import com.modu.backend.domain.ai.entity.AiJudgment;

import java.time.OffsetDateTime;

/**
 * 승인 대기 AI 판단 목록 항목 (S14P31B106-292 / 354)
 *
 * GET /api/v1/ai-agent/decisions/pending 응답 항목.
 *
 * S14P31B106-354 — 비보유자 추천(RECOMMENDED) 도 본 목록에 함께 노출되며,
 * FE 가 executionStatus 로 멘트를 구분 (APPROVAL_REQUIRED: "이대로 매수할까요?" /
 * RECOMMENDED: "이 종목 사실래요?"). stockTier / matchedRiskGrade 는 추천 컨텍스트
 * 풍부화용 (RECOMMENDED 외에는 null).
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
        OffsetDateTime approvalExpiresAt,
        AiExecutionStatus executionStatus,
        Integer stockTier,
        Integer matchedRiskGrade
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
                j.getApprovalExpiresAt(),
                j.getExecutionStatus(),
                j.getStockTier(),
                j.getMatchedRiskGrade()
        );
    }
}
