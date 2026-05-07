package com.modu.backend.domain.market.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modu.backend.domain.market.service.KisPlatformWebSocketKeyService;
import com.modu.backend.global.config.KisWebSocketProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
public class KisRealtimeUpstreamClient {

    private static final String SUBSCRIBE = "1";
    private static final String UNSUBSCRIBE = "2";

    private final ObjectMapper objectMapper;
    private final KisPlatformWebSocketKeyService webSocketKeyService;
    private final KisWebSocketProperties properties;
    private final KisRealtimeMessageParser parser;

    private final Map<KisRealtimeStreamType, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<KisRealtimeStreamType, Set<String>> subscriptions = new ConcurrentHashMap<>();
    private KisRealtimeSubscriptionManager subscriptionManager;

    public KisRealtimeUpstreamClient(
            ObjectMapper objectMapper,
            KisPlatformWebSocketKeyService webSocketKeyService,
            KisWebSocketProperties properties,
            KisRealtimeMessageParser parser
    ) {
        this.objectMapper = objectMapper;
        this.webSocketKeyService = webSocketKeyService;
        this.properties = properties;
        this.parser = parser;
    }

    void setSubscriptionManager(KisRealtimeSubscriptionManager subscriptionManager) {
        this.subscriptionManager = subscriptionManager;
    }

    public void subscribe(KisRealtimeStreamKey key) {
        subscriptions.computeIfAbsent(key.type(), ignored -> ConcurrentHashMap.newKeySet()).add(key.stockCode());
        sendSubscription(key, SUBSCRIBE);
    }

    public void unsubscribe(KisRealtimeStreamKey key) {
        Set<String> stockCodes = subscriptions.get(key.type());
        if (stockCodes != null) {
            stockCodes.remove(key.stockCode());
        }

        WebSocketSession session = sessions.get(key.type());
        if (session != null && session.isOpen()) {
            sendSubscription(key, UNSUBSCRIBE);
        }
    }

    private void sendSubscription(KisRealtimeStreamKey key, String trType) {
        try {
            WebSocketSession session = ensureSession(key.type());
            session.sendMessage(new TextMessage(subscriptionMessage(key, trType)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("KIS realtime subscription interrupted - trId: {}, stockCode: {}, error: {}",
                    key.type().trId(), key.stockCode(), e.getMessage());
        } catch (Exception e) {
            log.error("KIS realtime subscription send failed - trId: {}, stockCode: {}, error: {}",
                    key.type().trId(), key.stockCode(), e.getMessage());
        }
    }

    private WebSocketSession ensureSession(KisRealtimeStreamType type) throws Exception {
        WebSocketSession session = sessions.get(type);
        if (session != null && session.isOpen()) {
            return session;
        }

        synchronized (sessions) {
            session = sessions.get(type);
            if (session != null && session.isOpen()) {
                return session;
            }

            CompletableFuture<WebSocketSession> future = new StandardWebSocketClient()
                    .execute(new UpstreamHandler(type), properties.getUrl());
            WebSocketSession connectedSession;
            try {
                connectedSession = future.get(properties.getConnectionTimeoutMs(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new Exception("KIS WebSocket connection timeout - trId: " + type.trId()
                        + ", timeout: " + properties.getConnectionTimeoutMs() + "ms", e);
            }

            sessions.put(type, connectedSession);
            return connectedSession;
        }
    }

    private String subscriptionMessage(KisRealtimeStreamKey key, String trType) throws Exception {
        Map<String, Object> message = Map.of(
                "header", Map.of(
                        "approval_key", webSocketKeyService.getApprovalKey(),
                        "custtype", "P",
                        "tr_type", trType,
                        "content-type", "utf-8"
                ),
                "body", Map.of(
                        "input", Map.of(
                                "tr_id", key.type().trId(),
                                "tr_key", key.stockCode()
                        )
                )
        );
        return objectMapper.writeValueAsString(message);
    }

    private void restoreSubscriptions(KisRealtimeStreamType type) {
        Set<String> stockCodes = subscriptions.getOrDefault(type, Set.of());
        for (String stockCode : stockCodes) {
            sendSubscription(new KisRealtimeStreamKey(type, stockCode), SUBSCRIBE);
        }
    }

    private final class UpstreamHandler extends TextWebSocketHandler {

        private final KisRealtimeStreamType type;

        private UpstreamHandler(KisRealtimeStreamType type) {
            this.type = type;
        }

        @Override
        public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            String payload = message.getPayload();
            if (payload.startsWith("{")) {
                handleSystemMessage(session, payload);
                return;
            }

            parser.parse(payload).ifPresent(parsed -> {
                if (subscriptionManager == null) {
                    log.warn("KIS realtime subscription manager is not initialized, ignoring payload");
                    return;
                }
                subscriptionManager.broadcast(parsed.key(), parsed.payload());
            });
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            sessions.remove(type, session);
            if (hasActiveSubscriptions(type)) {
                Thread.ofVirtual().start(() -> reconnect(type));
            }
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            log.error("KIS realtime websocket error - trId: {}, error: {}", type.trId(), exception.getMessage());
        }

        private void handleSystemMessage(WebSocketSession session, String payload) throws Exception {
            JsonNode root = objectMapper.readTree(payload);
            String trId = root.path("header").path("tr_id").asText();
            if ("PINGPONG".equals(trId)) {
                session.sendMessage(new TextMessage(payload));
                return;
            }

            String resultCode = root.path("body").path("rt_cd").asText();
            if ("1".equals(resultCode)) {
                log.warn("KIS realtime subscription rejected - trId: {}, message: {}",
                        trId, root.path("body").path("msg1").asText());
            }
        }
    }

    private void reconnect(KisRealtimeStreamType type) {
        for (int attempt = 1; attempt <= properties.getReconnectMaxAttempts(); attempt++) {
            try {
                Thread.sleep(properties.getReconnectDelayMs());
                ensureSession(type);
                restoreSubscriptions(type);
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("KIS realtime reconnect failed - trId: {}, attempt: {}, error: {}",
                        type.trId(), attempt, e.getMessage());
            }
        }
    }

    private boolean hasActiveSubscriptions(KisRealtimeStreamType type) {
        Set<String> stockCodes = subscriptions.get(type);
        return stockCodes != null && !stockCodes.isEmpty();
    }
}
