package com.modu.backend.domain.trading.sse.redis;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Order SSE Redis Pub/Sub 메시지 — 채널 {@link OrderSseChannels#userChannel(long)} 로 publish.
 *
 * 주문/예약/체결 이벤트({@link com.modu.backend.domain.trading.sse.OrderSseEvent})와
 * AI 에이전트 메시지({@link com.modu.backend.domain.ai.sse.AgentMessageSseEvent}) 등
 * 다종 페이로드를 하나의 envelope 에 multiplex.
 *
 * 수신 측은 {@code eventName} 으로 SSE event name 을 결정하고 {@code payload} 를 그대로 클라이언트에 전달.
 *
 * @param eventName SSE event name ("order", "agent-message" 등)
 * @param payload   payload JSON (OrderSseEvent / AgentMessageSseEvent 등)
 * @param ts        발신 pod 의 발신 시각 (epoch ms, 진단/모니터링용)
 */
public record OrderSseEnvelope(
        String eventName,
        JsonNode payload,
        long ts
) {

    @JsonCreator
    public OrderSseEnvelope(
            @JsonProperty("eventName") String eventName,
            @JsonProperty("payload") JsonNode payload,
            @JsonProperty("ts") long ts
    ) {
        this.eventName = eventName;
        this.payload = payload;
        this.ts = ts;
    }
}
