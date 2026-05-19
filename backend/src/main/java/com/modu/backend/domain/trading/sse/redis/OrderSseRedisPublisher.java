package com.modu.backend.domain.trading.sse.redis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Order SSE Redis Pub/Sub 발행자.
 *
 * {@link com.modu.backend.domain.trading.sse.OrderSseEmitterManager#send 매니저의 send 경로}가 본 클래스를 호출 →
 * 사용자별 채널로 envelope publish → 모든 pod 의 {@link OrderSseRedisSubscriber} 가 수신.
 *
 * [실패 정책]
 * publish 실패는 ERROR 로그만 남기고 흡수 — 주문 흐름 자체는 안 끊는다 (fire-and-forget).
 * 시세 publisher({@link com.modu.backend.domain.market.feed.KisFeedPublisher}) 와 동일한 정책.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderSseRedisPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 사용자 채널에 envelope publish.
     *
     * @param userId    수신 대상 사용자 (SSE 연결 보유 pod 가 deliverLocal 처리)
     * @param eventName SSE event name (예: "order", "agent-message")
     * @param payload   직렬화 가능한 페이로드. {@code valueToTree} 로 JsonNode 변환 후 envelope 에 적재.
     */
    public void publish(long userId, String eventName, Object payload) {
        try {
            JsonNode payloadNode = objectMapper.valueToTree(payload);
            OrderSseEnvelope envelope = new OrderSseEnvelope(
                    eventName, payloadNode, System.currentTimeMillis()
            );
            String json = objectMapper.writeValueAsString(envelope);
            redisTemplate.convertAndSend(OrderSseChannels.userChannel(userId), json);
        } catch (Exception e) {
            log.error("[OrderSseRedisPublisher] publish 실패 - userId: {}, eventName: {}, error: {}",
                    userId, eventName, e.getMessage());
        }
    }
}
