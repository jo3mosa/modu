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
     * [필드 의미 및 허용 범위]
     *  - action       : "trade" / "hold"
     *  - side         : "buy" / "sell" (action="trade" 일 때만 값, "hold" 면 null)
     *  - orderAmount  : 매수 의도 금액(원). 양수. action="hold" 면 null 가능
     *  - targetPrice  : 익절 목표가(원). 양수. BE 가 Math.round → long 으로 변환 저장.
     *                   action="trade" 시 INVALID_ORDER_PARAMS 회피하려면 필수
     *  - stopLossPrice: 손절가(원). 양수. BE 가 Math.round → long 으로 변환 저장
     *  - reasonSummary: 판단 사유 텍스트. ai_judgments.judgment_reason 에 매핑
     *  - confidence   : AI 신뢰도 [0.0 ~ 1.0]. BE 가 round(c * 100) → 0~100 long 으로 변환 (0~100 clamp)
     *  - riskLevel    : "low" / "medium" / "high". "high" 면 APPROVAL_REQUIRED 분기
     *
     * [BE 검증]
     *  명세 범위를 벗어난 값(음수 가격 / confidence > 1.0 등) 은 SignalHandlerService 런타임 검증에서
     *  INVALID_ORDER_PARAMS 또는 0~100 clamp 처리. JSON 역직렬화 단계엔 별도 강제 없음.
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
