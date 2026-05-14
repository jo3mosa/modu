package com.modu.backend.domain.market.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class KisRealtimeSubscriptionManager {

    private final ObjectMapper objectMapper;
    private final KisRealtimeUpstreamClient upstreamClient;
    private final ConcurrentHashMap<KisRealtimeStreamKey, Set<WebSocketSession>> sessionsByKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, KisRealtimeStreamKey> keyBySessionId = new ConcurrentHashMap<>();
    /** 서버 사이드 구독 보유 카운트 (Position Monitor 등 프론트 세션 무관 구독 — S14P31B106-302) */
    private final ConcurrentHashMap<KisRealtimeStreamKey, AtomicInteger> serverHoldsByKey = new ConcurrentHashMap<>();

    public KisRealtimeSubscriptionManager(ObjectMapper objectMapper, KisRealtimeUpstreamClient upstreamClient) {
        this.objectMapper = objectMapper;
        this.upstreamClient = upstreamClient;
        this.upstreamClient.setSubscriptionManager(this);
    }

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

        if (firstSubscriber.get() && !hasServerHold(key)) {
            // 서버 사이드가 이미 구독 중이면 KIS 측은 이미 SUBSCRIBE 상태 — 중복 전송 회피
            upstreamClient.subscribe(key);
        }
    }

    /**
     * 서버 사이드 구독 등록 (Position Monitor 등 화면 무관 보유 종목 구독)
     *
     * 카운팅 동작:
     *  - 같은 key 에 대한 register 가 N 번 호출되면 카운트 N → unregisterServerSide 도 N 번 필요
     *  - 카운트 0→1 전환 시 프론트 세션이 없으면 upstream 에 SUBSCRIBE 전송
     */
    public void registerServerSide(KisRealtimeStreamKey key) {
        AtomicInteger count = serverHoldsByKey.computeIfAbsent(key, ignored -> new AtomicInteger(0));
        int prev = count.getAndIncrement();
        if (prev == 0 && !hasFrontendSessions(key)) {
            upstreamClient.subscribe(key);
        }
    }

    /**
     * 서버 사이드 구독 해제
     * 카운트가 0 으로 떨어지고 프론트 세션도 없으면 upstream UNSUBSCRIBE 전송
     */
    public void unregisterServerSide(KisRealtimeStreamKey key) {
        AtomicInteger count = serverHoldsByKey.get(key);
        if (count == null) return;

        int now = count.decrementAndGet();
        if (now <= 0) {
            serverHoldsByKey.remove(key, count);
            if (!hasFrontendSessions(key)) {
                upstreamClient.unsubscribe(key);
            }
        }
    }

    private boolean hasServerHold(KisRealtimeStreamKey key) {
        AtomicInteger count = serverHoldsByKey.get(key);
        return count != null && count.get() > 0;
    }

    private boolean hasFrontendSessions(KisRealtimeStreamKey key) {
        Set<WebSocketSession> sessions = sessionsByKey.get(key);
        return sessions != null && !sessions.isEmpty();
    }

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

        if (lastSubscriber.get() && !hasServerHold(key)) {
            // 서버 사이드 구독이 남아있으면 upstream 구독을 유지
            upstreamClient.unsubscribe(key);
        }
    }

    void broadcast(KisRealtimeStreamKey key, Object payload) {
        Set<WebSocketSession> sessions = sessionsByKey.get(key);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }

        TextMessage message;
        try {
            message = new TextMessage(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("Realtime websocket message serialization failed - trId: {}, stockCode: {}, error: {}",
                    key.type().trId(), key.stockCode(), e.getMessage());
            return;
        }

        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                continue;
            }
            try {
                session.sendMessage(message);
            } catch (Exception e) {
                log.warn("Realtime websocket send failed - sessionId: {}, trId: {}, stockCode: {}, error: {}",
                        session.getId(), key.type().trId(), key.stockCode(), e.getMessage());
            }
        }
    }
}
