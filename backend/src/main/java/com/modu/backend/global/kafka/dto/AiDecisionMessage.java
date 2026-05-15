package com.modu.backend.global.kafka.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.OffsetDateTime;

/**
 * ai.decision.generated 토픽 메시지 DTO
 *
 * 발행자: ai_agent (Python) — `ai_agent/app/graph/runner.py#run_and_publish`
 * 소비자: AiDecisionConsumer (S14P31B106-263)
 *
 * [네이밍]
 * AI 페이로드는 snake_case, 자바 필드는 camelCase.
 * 각 record 에 @JsonNaming(SnakeCaseStrategy) 로 매핑.
 *
 * [필수/선택 필드]
 *  - 필수: user_id, source_event_id, stock_code, final_decision, flow_status
 *  - 선택: created_at, debate, indicators_snapshot, decision_id (AI 합의 명세 일부)
 *
 * [멱등키]
 * AI 합의상 decision_id 가 멱등키이나 현재 ai_agent 미발행 상태.
 * BE 는 (source_event_id, user_id) partial unique index 로 멱등 보장.
 *
 * [타입 정책]
 * Kafka 메시지는 외부 시스템과의 계약이므로 enum 직접 사용 대신 String 으로 받는다.
 * 알 수 없는 값으로 깨지는 사고 방지. 검증/변환은 SignalHandlerService 책임.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AiDecisionMessage(
        Long userId,
        String sourceEventId,
        String stockCode,
        OffsetDateTime createdAt,
        String decisionId,
        FinalDecision finalDecision,
        Debate debate,
        JsonNode indicatorsSnapshot,
        String flowStatus
) {

    /**
     * AI 최종 결정 페이로드
     *
     * action="hold" → side / order_amount / target_price / stop_loss_price 가 null/0 일 수 있음
     * action="trade" → side ∈ {buy, sell} + 가격/금액 필드 필수
     * confidence: 0~1 float (BE 가 0~100 정수로 변환 저장)
     * target_price / stop_loss_price: float (BE 가 round 후 long 변환 저장)
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FinalDecision(
            String action,
            String side,
            Long orderAmount,
            Double targetPrice,
            Double stopLossPrice,
            String reasonSummary,
            Double confidence,
            String riskLevel
    ) {}

    /**
     * AI Bull/Bear 토론 결과
     *
     * winner: bull / bear / balanced
     * keySignals: 배열 — 원본 JSON 그대로 ai_judgments.key_signals 에 저장
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Debate(
            String bullClaim,
            String bearClaim,
            String winner,
            JsonNode keySignals
    ) {}
}
