package com.modu.backend.domain.market.feed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modu.backend.domain.market.websocket.KisRealtimeSubscriptionManager;
import com.modu.backend.global.config.KisProfiles;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * backend (REMOTE 모드) 전용 — gateway 가 publish 한 사용자별 tick 을 모두 수신.
 *
 * 패턴 구독: {@code kis:feed:tick:user:*} — 모든 사용자 채널을 한 번에.
 * 같은 종목을 N 명이 구독 중이면 envelope 이 N 번 도착하나, 종목 시세 자체는 공개 데이터라
 * 동일한 broadcast 가 중복 호출돼도 프론트는 마지막 값으로 갱신 → UX 영향 없음.
 *
 * 향후 사용자 수가 늘어 비효율이 문제되면 사용자 단위 동적 구독으로 진화.
 */
@Slf4j
@Component
@Profile(KisProfiles.NOT_GATEWAY)
@ConditionalOnProperty(name = "market.feed.client-mode", havingValue = "REMOTE")
@RequiredArgsConstructor
public class KisTickRedisSubscriber implements MessageListener {

    private final RedisMessageListenerContainer container;
    private final KisRealtimeSubscriptionManager subscriptionManager;
    private final ObjectMapper objectMapper;

    @PostConstruct
    void register() {
        container.addMessageListener(this, new PatternTopic(KisFeedChannels.TICK_USER_PREFIX + "*"));
        if (!container.isRunning()) {
            container.start();
        }
        log.info("[KisTickRedisSubscriber] pattern subscribed - {}*", KisFeedChannels.TICK_USER_PREFIX);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            KisTickEnvelope envelope = objectMapper.readValue(body, KisTickEnvelope.class);
            subscriptionManager.deliverRemoteTick(envelope);
        } catch (Exception e) {
            log.error("[KisTickRedisSubscriber] envelope handling failed - body: {}", body, e);
        }
    }
}
