package com.modu.backend.domain.ai.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.modu.backend.domain.ai.entity.AiJudgment;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "주문별 AI 판단 근거 상세 응답")
public record AiJudgmentDetailResponse(
        @Schema(description = "AI 판단 이력 ID", example = "101")
        Long judgmentId,

        @Schema(description = "AI 판단과 연결된 주문 ID", example = "5001")
        Long orderId,

        @Schema(description = "판단 대상 종목 코드", example = "005930")
        String stockCode,

        @Schema(description = "AI 판단 이벤트 유형", example = "PASSED", allowableValues = {"PASSED", "HOLD", "BLOCKED", "APPROVAL_REQUIRED"})
        String eventType,

        @Schema(description = "AI 판단 신뢰 점수. 0~100 기준 점수입니다.", example = "82")
        Long confidenceScore,

        @Schema(description = "판단 시점의 기술 지표, 시장 데이터 등 AI 판단 입력 스냅샷")
        JsonNode indicatorsSnapshot,

        @Schema(description = "AI 판단 사유", example = "거래량 증가와 단기 추세 개선으로 매수 조건을 충족했습니다.")
        String judgmentReason,

        @Schema(description = "AI 판단 일시", example = "2026-05-08T09:00:00+09:00")
        OffsetDateTime judgedAt
) {

    public static AiJudgmentDetailResponse from(AiJudgment judgment) {
        AiJudgmentResponseMapper.ValidatedJudgment validated = AiJudgmentResponseMapper.validate(judgment);

        return new AiJudgmentDetailResponse(
                judgment.getId(),
                judgment.getOrderId(),
                judgment.getStockCode(),
                validated.eventType(),
                validated.confidenceScore(),
                judgment.getIndicatorsSnapshot(),
                judgment.getJudgmentReason(),
                judgment.getJudgedAt()
        );
    }
}
