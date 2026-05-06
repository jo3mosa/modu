package com.modu.backend.domain.market.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 프론트 WebSocket 세션 구독 관리자
 *
 * [관리 기준]
 * - KisRealtimeStreamKey(type + stockCode) 단위 세션 그룹핑
 * - 첫 구독자 등록 시 KIS upstream subscribe
 * - 마지막 구독자 종료 시 KIS upstream unsubscribe
 * - 동일 구독 데이터 fan-out
 */
@Slf4j
@Component
public class KisRealtimeSubscriptionManager {

    private final ObjectMapper objectMapper;
    private final KisRealtimeUpstreamClient upstreamClient;
    private final ConcurrentHashMap<KisRealtimeStreamKey, Set<WebSocketSession>> sessionsByKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, KisRealtimeStreamKey> keyBySessionId = new ConcurrentHashMap<>();

    public KisRealtimeSubscriptionManager(ObjectMapper objectMapper, KisRealtimeUpstreamClient upstreamClient) {
        this.objectMapper = objectMapper;
        this.upstreamClient = upstreamClient;
        this.upstreamClient.setSubscriptionManager(this);
    }

    /**
     * 프론트 WebSocket 세션 구독 등록
     */
    public void register(WebSocketSession session, KisRealtimeStreamKey key) {
        AtomicBoolean firstSubscriber = new AtomicBoolean(false);

        sessionsByKey.compute(key, (ignored, sessions) -> {
            if (sessions == null) {
                sessions = ConcurrentHashMap.newKeySet();
                firstSubscriber.set(true);
            }
            sessions.add(session);
            return sessions;
        });
        keyBySessionId.put(session.getId(), key);

        if (firstSubscriber.get()) {
            upstreamClient.subscribe(key);
        }
    }

    /**
     * 프론트 WebSocket 세션 구독 해제
     */
    public void unregister(WebSocketSession session) {
        KisRealtimeStreamKey key = keyBySessionId.remove(session.getId());
        if (key == null) {
            return;
        }

        AtomicBoolean lastSubscriber = new AtomicBoolean(false);

        sessionsByKey.computeIfPresent(key, (ignored, sessions) -> {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                lastSubscriber.set(true);
                return null;
            }
            return sessions;
        });

        if (lastSubscriber.get()) {
            upstreamClient.unsubscribe(key);
        }
    }

    /**
     * 구독 세션 대상 실시간 데이터 fan-out
     */
    void broadcast(KisRealtimeStreamKey key, Object payload) {
        Set<WebSocketSession> sessions = sessionsByKey.get(key);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }

        try {
            TextMessage message = new TextMessage(objectMapper.writeValueAsString(payload));
            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    session.sendMessage(message);
                }
            }
        } catch (Exception e) {
            log.error("Realtime websocket broadcast failed - trId: {}, stockCode: {}, error: {}",
                    key.type().trId(), key.stockCode(), e.getMessage());
        }
    }
}
