package com.modu.backend.domain.market.feed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modu.backend.domain.market.websocket.KisRealtimeStreamKey;
import com.modu.backend.global.config.KisProfiles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * backend (REMOTE 모드) 전용 — KIS 직접 연결 대신 Redis control 채널 publish.
 *
 * 기존 KisRealtimeUpstreamClient (LOCAL) 의 외부 API (subscribe/unsubscribe) 를 같은 시그니처로 제공.
 * gateway 가 control 메시지를 받아 사용자 세션에 위임한다.
 *
 * userId 결정 책임은 호출자 (SubscriptionManager) — 프론트 WS 세션의 인증 정보로 식별.
 */
@Slf4j
@Component
@Profile(KisProfiles.NOT_GATEWAY)
@ConditionalOnProperty(name = "market.feed.client-mode", havingValue = "REMOTE")
@RequiredArgsConstructor
public class RemoteKisUpstreamClient {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MarketFeedProperties properties;

    public void subscribe(long userId, KisRealtimeStreamKey key) {
        publish(KisControlMessage.subscribe(userId, key.type().trId(), key.stockCode(), properties.resolvePodId()));
    }

    public void unsubscribe(long userId, KisRealtimeStreamKey key) {
        publish(KisControlMessage.unsubscribe(userId, key.type().trId(), key.stockCode(), properties.resolvePodId()));
    }

    /**
     * control 채널 발행은 반드시 표면화한다 (PR #166 review).
     *  - 수신자 0명 (Gateway 미구독/다운) → IllegalStateException
     *  - Redis 장애 등 예외 → IllegalStateException 으로 wrap 후 전파
     * 호출자(SubscriptionManager.register) 는 이 예외를 받아 매핑을 롤백한다.
     */
    private void publish(KisControlMessage msg) {
        String json;
        try {
            json = objectMapper.writeValueAsString(msg);
        } catch (Exception e) {
            log.error("[RemoteKisUpstreamClient] control 직렬화 실패 - userId: {}, trId: {}, trKey: {}",
                    msg.userId(), msg.trId(), msg.trKey(), e);
            throw new IllegalStateException("CONTROL serialization failed", e);
        }

        Long receivers;
        try {
            receivers = redisTemplate.convertAndSend(KisFeedChannels.CONTROL, json);
        } catch (Exception e) {
            log.error("[RemoteKisUpstreamClient] control publish 실패 - userId: {}, trId: {}, trKey: {}",
                    msg.userId(), msg.trId(), msg.trKey(), e);
            throw new IllegalStateException("CONTROL publish failed", e);
        }

        if (receivers == null || receivers == 0L) {
            log.error("[RemoteKisUpstreamClient] control 수신자 없음 (gateway down?) - userId: {}, trId: {}, trKey: {}",
                    msg.userId(), msg.trId(), msg.trKey());
            throw new IllegalStateException("No gateway subscriber for CONTROL channel");
        }
    }
}
