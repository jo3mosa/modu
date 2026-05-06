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

/**
 * KIS 실시간 WebSocket upstream 클라이언트
 *
 * [책임]
 * - KIS WebSocket 연결 생성/재사용
 * - 구독/해제 전문 송신
 * - PINGPONG 시스템 메시지 처리
 * - 연결 종료 시 재연결 및 기존 구독 복원
 *
 * [연결 전략]
 * - PRICE, ORDERBOOK 타입별 upstream 연결 공유
 * - 종목별 KIS 연결 생성 방지
 */
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

    /**
     * KIS 실시간 시세 구독 등록
     */
    public void subscribe(KisRealtimeStreamKey key) {
        subscriptions.computeIfAbsent(key.type(), ignored -> ConcurrentHashMap.newKeySet()).add(key.stockCode());
        sendSubscription(key, SUBSCRIBE);
    }

    /**
     * KIS 실시간 시세 구독 해제
     */
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

    /**
     * KIS 구독/해제 전문 송신
     */
    private void sendSubscription(KisRealtimeStreamKey key, String trType) {
        try {
            WebSocketSession session = ensureSession(key.type());
            session.sendMessage(new TextMessage(subscriptionMessage(key, trType)));
        } catch (Exception e) {
            log.error("KIS realtime subscription send failed - trId: {}, stockCode: {}, error: {}",
                    key.type().trId(), key.stockCode(), e.getMessage());
        }
    }

    /**
     * 타입별 upstream 세션 확보
     */
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
            WebSocketSession connectedSession = future.get();
            sessions.put(type, connectedSession);
            return connectedSession;
        }
    }

    /**
     * KIS WebSocket 구독/해제 JSON 전문 생성
     */
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

    /**
     * 재연결 후 기존 구독 복원
     */
    private void restoreSubscriptions(KisRealtimeStreamType type) {
        Set<String> stockCodes = subscriptions.getOrDefault(type, Set.of());
        for (String stockCode : stockCodes) {
            sendSubscription(new KisRealtimeStreamKey(type, stockCode), SUBSCRIBE);
        }
    }

    /**
     * KIS upstream 수신 핸들러
     */
    private final class UpstreamHandler extends TextWebSocketHandler {

        private final KisRealtimeStreamType type;

        private UpstreamHandler(KisRealtimeStreamType type) {
            this.type = type;
        }

        /**
         * KIS 수신 메시지 분기
         */
        @Override
        public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            String payload = message.getPayload();
            if (payload.startsWith("{")) {
                handleSystemMessage(session, payload);
                return;
            }

            parser.parse(payload).ifPresent(parsed -> subscriptionManager.broadcast(parsed.key(), parsed.payload()));
        }

        /**
         * KIS upstream 연결 종료 처리
         */
        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            sessions.remove(type, session);
            if (hasActiveSubscriptions(type)) {
                reconnect(type);
            }
        }

        /**
         * KIS upstream 전송 오류 로그
         */
        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            log.error("KIS realtime websocket error - trId: {}, error: {}", type.trId(), exception.getMessage());
        }

        /**
         * KIS JSON 시스템 메시지 처리
         */
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

    /**
     * upstream 재연결 및 구독 복원
     */
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

    /**
     * 타입별 활성 구독 존재 여부
     */
    private boolean hasActiveSubscriptions(KisRealtimeStreamType type) {
        Set<String> stockCodes = subscriptions.get(type);
        return stockCodes != null && !stockCodes.isEmpty();
    }
}
