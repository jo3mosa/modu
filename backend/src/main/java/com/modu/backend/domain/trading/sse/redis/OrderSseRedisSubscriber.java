package com.modu.backend.domain.trading.sse.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modu.backend.domain.trading.sse.OrderSseEmitterManager;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Order SSE Redis Pub/Sub 구독자 — 모든 backend pod 에 1 개씩 동작.
 *
 * 패턴 구독: {@link OrderSseChannels#USER_PATTERN}. 클러스터 내 어떤 pod 에서 publish 됐든
 * 모든 pod 가 envelope 을 수신하고, 자신의 emitter 맵에 해당 userId 가 있는 pod 만 실제 전송한다.
 *
 * [container 재사용]
 * 시세용으로 만든 {@link com.modu.backend.domain.market.feed.KisFeedRedisConfig#kisFeedRedisListenerContainer
 * kisFeedRedisListenerContainer} 빈을 그대로 공유. 컨테이너는 패턴 등록 레지스트리 역할이라 도메인 공유 안전.
 *
 * [실패 정책]
 * 잘못된 채널/JSON 파싱 실패는 ERROR 로그만 — emitter 측 전달 실패는 매니저가 자체 처리.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderSseRedisSubscriber implements MessageListener {

    private final RedisMessageListenerContainer container;
    private final OrderSseEmitterManager emitterManager;
    private final ObjectMapper objectMapper;

    @PostConstruct
    void register() {
        container.addMessageListener(this, new PatternTopic(OrderSseChannels.USER_PATTERN));
        if (!container.isRunning()) {
            container.start();
        }
        log.info("[OrderSseRedisSubscriber] pattern subscribed - {}", OrderSseChannels.USER_PATTERN);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        Long userId = OrderSseChannels.parseUserId(channel);
        if (userId == null) {
            log.warn("[OrderSseRedisSubscriber] invalid channel - {}", channel);
            return;
        }

        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            OrderSseEnvelope envelope = objectMapper.readValue(body, OrderSseEnvelope.class);
            emitterManager.deliverLocal(userId, envelope.eventName(), envelope.payload());
        } catch (Exception e) {
            // body 원문은 주문/에이전트 데이터 유출 위험 → 길이만 기록 (e 의 메시지로 원인 추적)
            log.error("[OrderSseRedisSubscriber] envelope handling failed - channel: {}, bodyLength: {}",
                    channel, body.length(), e);
        }
    }
}
