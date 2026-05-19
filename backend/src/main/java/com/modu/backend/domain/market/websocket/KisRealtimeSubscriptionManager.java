package com.modu.backend.domain.market.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modu.backend.domain.market.dto.OrderbookResponse;
import com.modu.backend.domain.market.dto.RealtimePriceResponse;
import com.modu.backend.domain.market.feed.KisTickEnvelope;
import com.modu.backend.domain.market.feed.RemoteKisUpstreamClient;
import com.modu.backend.global.config.KisProfiles;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 프론트엔드 WS 세션 ↔ 종목 매핑 + upstream subscribe/unsubscribe 위임.
 *
 * [모드]
 *  - LOCAL  : KisRealtimeUpstreamClient (공용 키, 단일 KIS 세션) — 기존 동작
 *  - REMOTE : RemoteKisUpstreamClient (Redis control 채널 → gateway) — replicas N 안전
 *
 * 활성 모드는 빈 의존성 주입 시점에 결정 (둘 중 하나만 활성). 의존성이 둘 다 없으면 시작 거부.
 *
 * [tick 수신]
 *  - LOCAL : KisRealtimeUpstreamClient 가 직접 broadcast(key, payload) 호출
 *  - REMOTE : KisTickRedisSubscriber 가 deliverRemoteTick(envelope) 호출
 */
@Slf4j
@Component
@Profile(KisProfiles.NOT_GATEWAY)
public class KisRealtimeSubscriptionManager {

    private final ObjectMapper objectMapper;
    private final KisRealtimeUpstreamClient localUpstream;   // LOCAL 모드. REMOTE 면 null.
    private final RemoteKisUpstreamClient remoteUpstream;    // REMOTE 모드. LOCAL 이면 null.

    private final ConcurrentHashMap<KisRealtimeStreamKey, Set<WebSocketSession>> sessionsByKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, KisRealtimeStreamKey> keyBySessionId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> userIdBySessionId = new ConcurrentHashMap<>();

    /** 서버 사이드 구독 보유 카운트 (Position Monitor 등 프론트 세션 무관 구독 — LOCAL 전용) */
    private final ConcurrentHashMap<KisRealtimeStreamKey, AtomicInteger> serverHoldsByKey = new ConcurrentHashMap<>();

    public KisRealtimeSubscriptionManager(
            ObjectMapper objectMapper,
            @Autowired(required = false) KisRealtimeUpstreamClient localUpstream,
            @Autowired(required = false) RemoteKisUpstreamClient remoteUpstream
    ) {
        this.objectMapper = objectMapper;
        this.localUpstream = localUpstream;
        this.remoteUpstream = remoteUpstream;
        if (localUpstream == null && remoteUpstream == null) {
            throw new IllegalStateException(
                    "No KIS upstream client active — check market.feed.client-mode (LOCAL or REMOTE)");
        }
        if (localUpstream != null) {
            localUpstream.setSubscriptionManager(this);
        }
        log.info("[SubscriptionManager] activated - mode: {}", localUpstream != null ? "LOCAL" : "REMOTE");
    }

    // ── 프론트엔드 세션 등록/해제 ──────────────────────────────────────────────

    public void register(WebSocketSession session, long userId, KisRealtimeStreamKey key) {
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
        userIdBySessionId.put(session.getId(), userId);

        if (firstSubscriber.get() && !hasServerHold(key)) {
            // 서버 사이드가 이미 구독 중이면 KIS 측은 이미 SUBSCRIBE 상태 — 중복 전송 회피
            try {
                subscribeUpstream(userId, key);
            } catch (Exception e) {
                // upstream SUBSCRIBE 실패(예: REMOTE 모드에서 gateway 미구독 / Redis 장애) →
                // 매핑이 남아 있으면 "구독된 줄 아는데 시세가 안 오는" 침묵의 실패가 됨.
                // 명시적 롤백 + 세션 종료로 프론트가 재연결 시도하도록 유도.
                log.error("[SubscriptionManager] upstream subscribe failed — rollback. userId: {}, key: {}",
                        userId, key, e);
                rollbackRegister(session, key);
                try { session.close(CloseStatus.SERVICE_RESTARTED); } catch (Exception ignored) {}
                throw e;
            }
        }
    }

    /** register() 의 매핑 등록을 되돌린다. upstream 실패 케이스에서만 호출. */
    private void rollbackRegister(WebSocketSession session, KisRealtimeStreamKey key) {
        keyBySessionId.remove(session.getId());
        userIdBySessionId.remove(session.getId());
        sessionsByKey.computeIfPresent(key, (ignored, sessions) -> {
            sessions.remove(session);
            return sessions.isEmpty() ? null : sessions;
        });
    }

    public void unregister(WebSocketSession session) {
        KisRealtimeStreamKey key = keyBySessionId.remove(session.getId());
        Long userId = userIdBySessionId.remove(session.getId());
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
            if (userId == null) {
                // 비정상 상태 — register 시 userId 추적 누락. upstream 해제는 skip (잘못된 userId 전송 방지).
                log.warn("[SubscriptionManager] unregister skip upstream — userId missing. sessionId: {}, key: {}",
                        session.getId(), key);
                return;
            }
            unsubscribeUpstream(userId, key);
        }
    }

    // ── 서버 사이드 보유 (LOCAL 전용) ───────────────────────────────────────────

    /**
     * 서버 사이드 구독 등록 (Position Monitor 등 화면 무관 보유 종목 구독)
     *
     * REMOTE 모드에서는 이 메서드 호출이 일어나면 안 됨 — 호출자(PositionHoldingSubscriber)가 LOCAL 전용이라
     * Spring 이 빈 자체를 활성하지 않음. 안전망으로 no-op + WARN.
     */
    public void registerServerSide(KisRealtimeStreamKey key) {
        if (localUpstream == null) {
            log.warn("[SubscriptionManager] registerServerSide called outside LOCAL mode — ignored. key: {}", key);
            return;
        }
        AtomicInteger count = serverHoldsByKey.computeIfAbsent(key, ignored -> new AtomicInteger(0));
        int prev = count.getAndIncrement();
        if (prev == 0 && !hasFrontendSessions(key)) {
            localUpstream.subscribe(key);
        }
    }

    public void unregisterServerSide(KisRealtimeStreamKey key) {
        if (localUpstream == null) return;
        AtomicInteger count = serverHoldsByKey.get(key);
        if (count == null) return;

        int now = count.decrementAndGet();
        if (now <= 0) {
            serverHoldsByKey.remove(key, count);
            if (!hasFrontendSessions(key)) {
                localUpstream.unsubscribe(key);
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

    // ── upstream 위임 (LOCAL/REMOTE 분기) ──────────────────────────────────────

    private void subscribeUpstream(long userId, KisRealtimeStreamKey key) {
        if (localUpstream != null) {
            localUpstream.subscribe(key);
        } else if (remoteUpstream != null) {
            remoteUpstream.subscribe(userId, key);
        }
    }

    private void unsubscribeUpstream(long userId, KisRealtimeStreamKey key) {
        if (localUpstream != null) {
            localUpstream.unsubscribe(key);
        } else if (remoteUpstream != null) {
            remoteUpstream.unsubscribe(userId, key);
        }
    }

    // ── tick broadcast ────────────────────────────────────────────────────────

    /** LOCAL 모드: KisRealtimeUpstreamClient 가 직접 호출 */
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

    /**
     * REMOTE 모드: KisTickRedisSubscriber 가 envelope 받으면 호출.
     * envelope.payload (JsonNode) 를 trId 별 타입으로 변환 후 broadcast.
     */
    public void deliverRemoteTick(KisTickEnvelope envelope) {
        KisRealtimeStreamType type;
        try {
            type = KisRealtimeStreamType.fromTrId(envelope.trId());
        } catch (IllegalArgumentException e) {
            log.warn("[SubscriptionManager] unsupported trId from remote - trId: {}", envelope.trId());
            return;
        }
        if (type != KisRealtimeStreamType.PRICE && type != KisRealtimeStreamType.ORDERBOOK) {
            // 체결통보는 본 채널이 아님 — 안전망
            return;
        }
        Class<?> targetType = type == KisRealtimeStreamType.PRICE
                ? RealtimePriceResponse.class
                : OrderbookResponse.class;
        Object payload;
        try {
            payload = objectMapper.treeToValue(envelope.payload(), targetType);
        } catch (Exception e) {
            log.error("[SubscriptionManager] remote tick payload 변환 실패 - trId: {}, stockCode: {}",
                    envelope.trId(), envelope.stockCode(), e);
            return;
        }
        broadcast(new KisRealtimeStreamKey(type, envelope.stockCode()), payload);
    }
}
