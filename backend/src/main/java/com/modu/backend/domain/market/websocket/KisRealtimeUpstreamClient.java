package com.modu.backend.domain.market.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modu.backend.domain.market.cache.RealtimePriceCacheService;
import com.modu.backend.domain.market.dto.RealtimePriceResponse;
import com.modu.backend.domain.market.service.KisPlatformWebSocketKeyService;
import com.modu.backend.global.config.KisProfiles;
import com.modu.backend.global.config.KisWebSocketProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * KIS 실시간 WebSocket Upstream 클라이언트 — 단일 세션 multiplex
 *
 * [세션 모델]
 *   KIS 는 appkey 당 동시 WebSocket 세션 1개만 허용 ("ALREADY IN USE appkey" 거부).
 *   따라서 시세 체결가 (H0STCNT0) / 시세 호가 (H0STASP0) / 체결통보 (H0STCNI0) 등
 *   모든 TR_ID 를 단일 세션 내 SUBSCRIBE 로 multiplex.
 *
 * [확장 — 외부 TR 핸들러 등록]
 *   체결통보 (H0STCNI0) 처럼 AES 복호화 / 별도 dispatch 가 필요한 TR 은
 *   registerSystemHandler / registerDataHandler 로 외부 처리기를 주입.
 *   기본 시세 (H0STCNT0 / H0STASP0) 는 본 클래스 내부 파서 + broadcast + 캐시.
 *
 * [재연결]
 *   세션 끊김 + 활성 구독 존재 시 가상 스레드 비동기 재연결.
 *   재연결 후 시세 종목 구독 + 외부 핸들러 재구독 (registerReconnectListener) 자동 복원.
 */
@Slf4j
@Component
@Profile(KisProfiles.NOT_GATEWAY)
@ConditionalOnProperty(name = "market.feed.client-mode", havingValue = "LOCAL", matchIfMissing = true)
public class KisRealtimeUpstreamClient {

    private static final String SUBSCRIBE = "1";
    private static final String UNSUBSCRIBE = "2";
    private static final long RECOVERY_COOLDOWN_MS = 30_000L;

    private final ObjectMapper objectMapper;
    private final KisPlatformWebSocketKeyService webSocketKeyService;
    private final KisWebSocketProperties properties;
    private final KisRealtimeMessageParser parser;
    private final RealtimePriceCacheService realtimePriceCacheService;

    /** 단일 WebSocket 세션 — 모든 TR 공유 */
    private volatile WebSocketSession session;
    /** TR 종류별 현재 구독 종목 코드 — 재연결 시 복원 */
    private final Map<KisRealtimeStreamType, Set<String>> subscriptions = new ConcurrentHashMap<>();
    /** approval_key 복구 cooldown */
    private final AtomicLong lastRecoveryAt = new AtomicLong(0L);

    /** SubscriptionManager 주입 (순환 참조 회피용 setter) */
    private KisRealtimeSubscriptionManager subscriptionManager;

    /** 외부 TR 핸들러 — 키: TR_ID, 값: 시스템 메시지 콜백 */
    private final Map<String, BiConsumer<JsonNode, WebSocketSession>> systemHandlers = new ConcurrentHashMap<>();
    /** 외부 TR 핸들러 — 키: TR_ID, 값: 데이터 메시지 콜백 (암호화 포함 raw 페이로드) */
    private final Map<String, Consumer<String>> dataHandlers = new ConcurrentHashMap<>();
    /** 재연결 직후 호출 — 외부 핸들러가 자신의 구독을 복원할 기회 */
    private final Set<Runnable> reconnectListeners = ConcurrentHashMap.newKeySet();

    public KisRealtimeUpstreamClient(
            ObjectMapper objectMapper,
            KisPlatformWebSocketKeyService webSocketKeyService,
            KisWebSocketProperties properties,
            KisRealtimeMessageParser parser,
            RealtimePriceCacheService realtimePriceCacheService
    ) {
        this.objectMapper = objectMapper;
        this.webSocketKeyService = webSocketKeyService;
        this.properties = properties;
        this.parser = parser;
        this.realtimePriceCacheService = realtimePriceCacheService;
    }

    void setSubscriptionManager(KisRealtimeSubscriptionManager subscriptionManager) {
        this.subscriptionManager = subscriptionManager;
    }

    // ───────────────────────────────────────────────────────────────────
    // 시세 — 종목 단위 SUBSCRIBE
    // ───────────────────────────────────────────────────────────────────

    public void subscribe(KisRealtimeStreamKey key) {
        subscriptions.computeIfAbsent(key.type(), ignored -> ConcurrentHashMap.newKeySet()).add(key.stockCode());
        sendSubscription(key.type().trId(), key.stockCode(), SUBSCRIBE);
    }

    public void unsubscribe(KisRealtimeStreamKey key) {
        Set<String> stockCodes = subscriptions.get(key.type());
        if (stockCodes != null) {
            stockCodes.remove(key.stockCode());
        }
        if (session != null && session.isOpen()) {
            sendSubscription(key.type().trId(), key.stockCode(), UNSUBSCRIBE);
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // 외부 TR — 핸들러 등록 + 메시지 발신 API
    // ───────────────────────────────────────────────────────────────────

    /** 외부 TR 시스템 메시지 핸들러 등록 (예: H0STCNI0 SUBSCRIBE 응답에서 AES IV/Key 추출) */
    public void registerSystemHandler(String trId, BiConsumer<JsonNode, WebSocketSession> handler) {
        systemHandlers.put(trId, handler);
    }

    /** 외부 TR 데이터 메시지 핸들러 등록 (예: H0STCNI0 암호 페이로드 복호화) */
    public void registerDataHandler(String trId, Consumer<String> handler) {
        dataHandlers.put(trId, handler);
    }

    /** 재연결 직후 콜백 등록 (외부 핸들러가 자신의 구독을 재발신) */
    public void registerReconnectListener(Runnable listener) {
        reconnectListeners.add(listener);
    }

    /** 외부 핸들러가 SUBSCRIBE 메시지를 발신할 때 사용 — 세션 보장 + 직렬화 send */
    public void send(String payload) throws Exception {
        WebSocketSession active = ensureSession();
        synchronized (active) {
            active.sendMessage(new TextMessage(payload));
        }
    }

    /** 외부 핸들러용 — 표준 SUBSCRIBE 메시지 생성 (approval_key 캐시 자동 사용) */
    public String buildSubscriptionMessage(String trId, String trKey, boolean subscribe) throws Exception {
        return subscriptionPayload(trId, trKey, subscribe ? SUBSCRIBE : UNSUBSCRIBE);
    }

    // ───────────────────────────────────────────────────────────────────
    // 공통 — 세션 보장 / 메시지 직렬화
    // ───────────────────────────────────────────────────────────────────

    private void sendSubscription(String trId, String trKey, String trType) {
        try {
            String payload = subscriptionPayload(trId, trKey, trType);
            send(payload);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("KIS realtime subscription interrupted - trId: {}, trKey: {}", trId, trKey);
        } catch (Exception e) {
            log.error("KIS realtime subscription send failed - trId: {}, trKey: {}, error: {}",
                    trId, trKey, e.getMessage());
        }
    }

    private WebSocketSession ensureSession() throws Exception {
        WebSocketSession current = session;
        if (current != null && current.isOpen()) return current;

        synchronized (this) {
            current = session;
            if (current != null && current.isOpen()) return current;

            // Origin 헤더 미설정 시 KIS 가 즉시 close(1006) 거부
            WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
            headers.setOrigin("http://localhost");
            CompletableFuture<WebSocketSession> future = new StandardWebSocketClient()
                    .execute(new UpstreamHandler(), headers, URI.create(properties.getUrl()));
            WebSocketSession connected;
            try {
                connected = future.get(properties.getConnectionTimeoutMs(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new Exception("KIS WebSocket connection timeout - timeout: "
                        + properties.getConnectionTimeoutMs() + "ms", e);
            }
            session = connected;
            return connected;
        }
    }

    private String subscriptionPayload(String trId, String trKey, String trType) throws Exception {
        Map<String, Object> message = Map.of(
                "header", Map.of(
                        "approval_key", webSocketKeyService.getApprovalKey(),
                        "custtype", "P",
                        "tr_type", trType,
                        "content-type", "utf-8"
                ),
                "body", Map.of(
                        "input", Map.of(
                                "tr_id", trId,
                                "tr_key", trKey
                        )
                )
        );
        return objectMapper.writeValueAsString(message);
    }

    private void restoreAllSubscriptions() {
        // 시세 구독 복원
        subscriptions.forEach((type, stockCodes) -> {
            for (String code : stockCodes) {
                sendSubscription(type.trId(), code, SUBSCRIBE);
            }
        });
        // 외부 핸들러 재구독 (예: 체결통보 CANO 들)
        for (Runnable listener : reconnectListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                log.error("KIS realtime reconnect listener failed", e);
            }
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // WebSocket 핸들러
    // ───────────────────────────────────────────────────────────────────

    private final class UpstreamHandler extends TextWebSocketHandler {

        @Override
        public void handleTextMessage(WebSocketSession s, TextMessage message) throws Exception {
            String payload = message.getPayload();
            if (payload.startsWith("{")) {
                handleSystemMessage(s, payload);
                return;
            }
            handleDataMessage(payload);
        }

        @Override
        public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
            log.warn("KIS realtime websocket closed - status: {}", status);
            if (session == s) session = null;
            if (hasActiveSubscriptions()) {
                Thread.ofVirtual().start(KisRealtimeUpstreamClient.this::reconnect);
            }
        }

        @Override
        public void handleTransportError(WebSocketSession s, Throwable exception) {
            log.error("KIS realtime websocket error - {}", exception.getMessage());
        }

        private void handleSystemMessage(WebSocketSession s, String payload) throws Exception {
            JsonNode root = objectMapper.readTree(payload);
            String trId = root.path("header").path("tr_id").asText();

            if ("PINGPONG".equals(trId)) {
                synchronized (s) {
                    s.sendMessage(new TextMessage(payload));
                }
                return;
            }

            String resultCode = root.path("body").path("rt_cd").asText();
            if ("1".equals(resultCode)) {
                String msg = root.path("body").path("msg1").asText();
                log.warn("KIS realtime subscription rejected - trId: {}, message: {}", trId, msg);
                if (msg.contains("invalid approval") && tryAcquireRecovery()) {
                    recoverInvalidApproval();
                }
                return;
            }

            // 등록된 외부 핸들러 우선 (예: H0STCNI0 → cipher 초기화)
            BiConsumer<JsonNode, WebSocketSession> external = systemHandlers.get(trId);
            if (external != null) {
                try {
                    external.accept(root, s);
                } catch (Exception e) {
                    log.error("KIS realtime external system handler failed - trId: {}", trId, e);
                }
            }
        }

        private void handleDataMessage(String raw) {
            // 페이로드 형식: "0|TR_ID|cnt|data..." — TR_ID 추출
            int firstPipe = raw.indexOf('|');
            int secondPipe = firstPipe < 0 ? -1 : raw.indexOf('|', firstPipe + 1);
            if (secondPipe < 0) {
                log.warn("KIS realtime data message format invalid - drop");
                return;
            }
            String trId = raw.substring(firstPipe + 1, secondPipe);

            Consumer<String> external = dataHandlers.get(trId);
            if (external != null) {
                try {
                    external.accept(raw);
                } catch (Exception e) {
                    log.error("KIS realtime external data handler failed - trId: {}", trId, e);
                }
                return;
            }

            // 기본 시세 처리 — broadcast + 캐시
            parser.parse(raw).ifPresent(parsed -> {
                if (subscriptionManager != null) {
                    subscriptionManager.broadcast(parsed.key(), parsed.payload());
                } else {
                    log.warn("KIS realtime subscription manager is not initialized, skipping broadcast");
                }
                if (parsed.payload() instanceof RealtimePriceResponse price) {
                    realtimePriceCacheService.cache(price);
                }
            });
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // 재연결 / approval_key 복구
    // ───────────────────────────────────────────────────────────────────

    private void reconnect() {
        for (int attempt = 1; attempt <= properties.getReconnectMaxAttempts(); attempt++) {
            try {
                Thread.sleep(properties.getReconnectDelayMs());
                ensureSession();
                restoreAllSubscriptions();
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("KIS realtime reconnect failed - attempt: {}, error: {}",
                        attempt, e.getMessage());
            }
        }
    }

    private boolean hasActiveSubscriptions() {
        for (Set<String> stockCodes : subscriptions.values()) {
            if (stockCodes != null && !stockCodes.isEmpty()) return true;
        }
        return !reconnectListeners.isEmpty();
    }

    private boolean tryAcquireRecovery() {
        long now = System.currentTimeMillis();
        long prev = lastRecoveryAt.get();
        if (now - prev < RECOVERY_COOLDOWN_MS) return false;
        return lastRecoveryAt.compareAndSet(prev, now);
    }

    private void recoverInvalidApproval() {
        log.warn("KIS realtime approval key 복구 시작");
        webSocketKeyService.evictApprovalKey();
        Thread.ofVirtual().start(this::restoreAllSubscriptions);
    }
}
