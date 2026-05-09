package com.modu.backend.domain.ai.dto;

import com.modu.backend.domain.ai.entity.AiJudgment;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "AI 판단 이력 요약 응답")
public record AiJudgmentSummaryResponse(
        @Schema(description = "AI 판단 이력 ID", example = "101")
        Long judgmentId,

        @Schema(description = "AI 판단과 연결된 주문 ID. 주문과 연결되지 않은 판단은 null일 수 있습니다.", example = "5001", nullable = true)
        Long orderId,

        @Schema(description = "판단 대상 종목 코드", example = "005930")
        String stockCode,

        @Schema(description = "AI 판단 이벤트 유형", example = "PASSED", allowableValues = {"PASSED", "HOLD", "BLOCKED", "APPROVAL_REQUIRED"})
        String eventType,

        @Schema(description = "AI 판단 신뢰 점수. 0~100 기준 점수입니다.", example = "82")
        Long confidenceScore,

        @Schema(description = "AI 판단 사유 요약", example = "거래량 증가와 단기 추세 개선으로 매수 조건을 충족했습니다.")
        String judgmentReason,

        @Schema(description = "AI 판단 일시", example = "2026-05-08T09:00:00+09:00")
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
