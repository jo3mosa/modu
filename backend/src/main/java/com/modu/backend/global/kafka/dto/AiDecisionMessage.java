package com.modu.backend.global.kafka.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * ai.decision.generated 토픽 메시지 DTO
 *
 * AI 에이전트(ai_agent/app/graph/runner.py)가 발행하는 JSON 페이로드를 매핑한다.
 * 알 수 없는 필드는 무시(@JsonIgnoreProperties)하여 AI 에이전트 스펙 변경에 유연하게 대응한다.
 *
 * [주요 처리 규칙]
 * - finalDecision.action == "hold" → decision = HOLD, 가격/수량 필드 null
 * - finalDecision.action == "trade" → finalDecision.side(buy/sell)를 BUY/SELL로 변환
 * - executionResult.status == "skipped" / "failed" → 결정은 저장, orderId = null
 * - confidence: float 0~1 → int 0~100 변환 후 ai_judgments.confidence_score에 저장
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiDecisionMessage(

        @JsonProperty("user_id")
        Long userId,

        @JsonProperty("source_event_id")
        String sourceEventId,           // 멱등성 키 (source_event_id + user_id 조합으로 중복 방지)

        @JsonProperty("stock_code")
        String stockCode,

        @JsonProperty("final_decision")
        FinalDecision finalDecision,    // 매수/매도/관망 판단 상세

        @JsonProperty("bull_claim")
        String bullClaim,               // Bull 애널리스트 주장 (nullable)

        @JsonProperty("bear_claim")
        String bearClaim,               // Bear 애널리스트 주장 (nullable)

        @JsonProperty("winning_side")
        String winningSide,             // bull / bear / balanced

        @JsonProperty("key_signals")
        List<String> keySignals,        // 판단에 사용된 주요 시그널 목록

        @JsonProperty("indicators_snapshot")
        JsonNode indicatorsSnapshot,    // 판단 시점 기술/펀더멘털 지표 스냅샷 (JSONB)

        @JsonProperty("execution_result")
        ExecutionResult executionResult, // AI 에이전트 측 실행 결과

        @JsonProperty("flow_status")
        String flowStatus               // completed / hold 등 전체 플로우 상태

) {

    /**
     * AI 최종 판단 상세
     * action=trade이면 side(buy/sell)로 매수/매도 구분
     * action=hold이면 side는 null
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FinalDecision(

            @JsonProperty("action")
            String action,              // "trade" / "hold"

            @JsonProperty("asset")
            String asset,

            @JsonProperty("side")
            String side,                // "buy" / "sell" (action=hold면 null)

            @JsonProperty("order_amount")
            Long orderAmount,           // 주문 금액 (action=hold면 null)

            @JsonProperty("target_price")
            Long targetPrice,           // 목표가 (action=hold면 null)

            @JsonProperty("stop_loss_price")
            Long stopLossPrice,         // 손절가 (action=hold면 null)

            @JsonProperty("reason_summary")
            String reasonSummary,       // 판단 사유 요약 → ai_judgments.judgment_reason

            @JsonProperty("confidence")
            Double confidence,          // 신뢰도 0.0~1.0 → *100 → Long으로 저장

            @JsonProperty("risk_level")
            String riskLevel,           // 리스크 등급 → ai_judgments.risk_grade

            @JsonProperty("user_message")
            String userMessage          // 사용자에게 보여줄 메시지
    ) {}

    /**
     * AI 에이전트 측 실행 결과
     * 백엔드는 이 결과와 무관하게 자체적으로 KIS 주문을 처리한다.
     * orderId는 AI 에이전트의 mock 값이므로 백엔드 DB에 저장하지 않는다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExecutionResult(

            @JsonProperty("status")
            String status,              // "success" / "skipped" / "failed"

            @JsonProperty("order_id")
            String orderId,             // AI mock 주문 ID (참고용, 백엔드 미사용)

            @JsonProperty("stock_code")
            String stockCode,

            @JsonProperty("side")
            String side,

            @JsonProperty("quantity")
            Integer quantity
    ) {}

    /**
     * finalDecision.action + side → 백엔드 decision 문자열 변환
     * action=hold → "HOLD"
     * action=trade, side=buy → "BUY"
     * action=trade, side=sell → "SELL"
     */
    public String resolveDecision() {
        if (finalDecision == null || "hold".equalsIgnoreCase(finalDecision.action())) {
            return "HOLD";
        }
        return finalDecision.side() != null
                ? finalDecision.side().toUpperCase()
                : "HOLD";
    }

    /**
     * confidence(0.0~1.0) → confidenceScore(0~100) 변환
     * finalDecision이 null이거나 confidence가 null이면 0 반환
     */
    public long resolveConfidenceScore() {
        if (finalDecision == null || finalDecision.confidence() == null) return 0L;
        return Math.round(finalDecision.confidence() * 100);
    }
}
