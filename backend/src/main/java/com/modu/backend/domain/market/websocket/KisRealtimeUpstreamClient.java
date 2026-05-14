package com.modu.backend.domain.market.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modu.backend.domain.market.cache.RealtimePriceCacheService;
import com.modu.backend.domain.market.dto.RealtimePriceResponse;
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

/**
 * KIS 실시간 시세 WebSocket Upstream 클라이언트
 *
 * [역할]
 * 플랫폼 KIS 계정 1개로 KIS WebSocket 에 연결하여 종목별 체결가/호가 등을 구독·수신.
 * 수신된 메시지는 (1) 프론트 세션 broadcast (SubscriptionManager) (2) Redis 캐시 (CacheService)
 * 두 경로로 전파.
 *
 * [세션 모델]
 *  - TR 종류(체결가/호가 등) 별 1개의 WebSocket 세션 (사용 종목 다수가 같은 세션 공유)
 *  - 종목 단위 구독은 같은 세션 안에서 메시지 단위로 수행 (SUBSCRIBE/UNSUBSCRIBE)
 *
 * [재연결]
 *  - 세션 끊김 + 활성 구독 존재 시 가상 스레드로 비동기 재연결
 *  - 재연결 후 기존 구독 종목 자동 복원
 *
 * [구독 한도]
 *  - KIS 플랫폼 계정 1개 기준 동시 구독 종목 수 한계 존재 (약 41건, KIS 정책)
 *  - "프론트가 화면 띄운 종목" 만 구독되는 구조 — Position Monitor 등 화면 무관 구독 필요 시 별도 트리거 필요
 */
@Slf4j
@Component
public class KisRealtimeUpstreamClient {

    /** KIS WebSocket tr_type — "1" = 구독 시작 */
    private static final String SUBSCRIBE = "1";
    /** KIS WebSocket tr_type — "2" = 구독 해제 */
    private static final String UNSUBSCRIBE = "2";

    private final ObjectMapper objectMapper;
    private final KisPlatformWebSocketKeyService webSocketKeyService;
    private final KisWebSocketProperties properties;
    private final KisRealtimeMessageParser parser;
    private final RealtimePriceCacheService realtimePriceCacheService;

    /** TR 종류별 활성 WebSocket 세션 */
    private final Map<KisRealtimeStreamType, WebSocketSession> sessions = new ConcurrentHashMap<>();
    /** TR 종류별 현재 구독 중인 종목 코드 집합 — 재연결 시 복원 기준 */
    private final Map<KisRealtimeStreamType, Set<String>> subscriptions = new ConcurrentHashMap<>();
    /** 메시지 수신 시 프론트 broadcast 위임 대상. 순환 참조 회피용으로 setter 주입 */
    private KisRealtimeSubscriptionManager subscriptionManager;

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

    /**
     * SubscriptionManager 주입 (순환 참조 회피)
     * SubscriptionManager 생성자에서 setSubscriptionManager(this) 호출
     */
    void setSubscriptionManager(KisRealtimeSubscriptionManager subscriptionManager) {
        this.subscriptionManager = subscriptionManager;
    }

    /**
     * KIS 종목 구독 시작
     * 같은 TR 종류의 첫 종목이면 WebSocket 세션 생성 후 SUBSCRIBE 메시지 발신
     */
    public void subscribe(KisRealtimeStreamKey key) {
        subscriptions.computeIfAbsent(key.type(), ignored -> ConcurrentHashMap.newKeySet()).add(key.stockCode());
        sendSubscription(key, SUBSCRIBE);
    }

    /**
     * KIS 종목 구독 해제
     * 세션 자체는 유지 (같은 TR 의 다른 종목이 사용 가능). 세션 닫기는 외부 호출자 책임.
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
     * SUBSCRIBE/UNSUBSCRIBE 메시지 발신 공통 처리
     * 세션 준비 → 메시지 직렬화 → 전송. 실패 시 ERROR 로그만 (호출 흐름 차단 X)
     */
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

    /**
     * 해당 TR 종류의 활성 WebSocket 세션 보장 (없거나 닫힌 경우 신규 연결)
     *
     * synchronized 블록 안에서 double-checked locking — 동시에 여러 스레드가 같은 type 의
     * 첫 구독을 요청해도 한 번의 연결만 수립
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
            WebSocketSession connectedSession;
            try {
                connectedSession = future.get(properties.getConnectionTimeoutMs(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (TimeoutException e) {
                // 연결 대기 timeout 시 future 강제 취소 — 좀비 연결 방지
                future.cancel(true);
                throw new Exception("KIS WebSocket connection timeout - trId: " + type.trId()
                        + ", timeout: " + properties.getConnectionTimeoutMs() + "ms", e);
            }

            sessions.put(type, connectedSession);
            return connectedSession;
        }
    }

    /**
     * KIS WebSocket 구독 메시지 JSON 직렬화
     * header.approval_key 는 플랫폼 KIS 계정의 WebSocket 승인키 (캐시됨, 23h TTL)
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
     * 재연결 직후 기존 구독 종목 일괄 복원
     * subscriptions 맵의 종목 코드들을 모두 다시 SUBSCRIBE 메시지로 전송
     */
    private void restoreSubscriptions(KisRealtimeStreamType type) {
        Set<String> stockCodes = subscriptions.getOrDefault(type, Set.of());
        for (String stockCode : stockCodes) {
            sendSubscription(new KisRealtimeStreamKey(type, stockCode), SUBSCRIBE);
        }
    }

    /**
     * KIS WebSocket Upstream 핸들러 (TR 종류별 1 인스턴스)
     *
     * KIS 가 보내는 메시지 형식:
     *  - 시세 데이터 메시지: 파이프(|) 구분 텍스트 — parser 가 RealtimePriceResponse 등으로 변환
     *  - 시스템 메시지 (PINGPONG / 구독 응답): JSON — handleSystemMessage 가 처리
     */
    private final class UpstreamHandler extends TextWebSocketHandler {

        private final KisRealtimeStreamType type;

        private UpstreamHandler(KisRealtimeStreamType type) {
            this.type = type;
        }

        /**
         * KIS WebSocket 수신 메시지 처리
         *
         * 흐름:
         *  1. JSON 시작 ('{') → 시스템 메시지 (PINGPONG / 구독 응답) 처리
         *  2. 외 → 시세 데이터로 parsing 후 broadcast + Redis 캐시
         */
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
                // (1) 프론트 broadcast — 시세 화면 표시용
                subscriptionManager.broadcast(parsed.key(), parsed.payload());
                // (2) Redis 캐시 (체결가 메시지만) — Position Monitor / AI 가 polling 으로 조회
                if (parsed.payload() instanceof RealtimePriceResponse price) {
                    realtimePriceCacheService.cache(price);
                }
            });
        }

        /**
         * WebSocket 연결 종료 시 처리
         * 활성 구독 종목이 남아있으면 가상 스레드로 비동기 재연결 시도
         */
        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            sessions.remove(type, session);
            if (hasActiveSubscriptions(type)) {
                Thread.ofVirtual().start(() -> reconnect(type));
            }
        }

        /**
         * Transport 오류 — ERROR 로그만, 후속 처리는 afterConnectionClosed 에 위임
         */
        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            log.error("KIS realtime websocket error - trId: {}, error: {}", type.trId(), exception.getMessage());
        }

        /**
         * KIS 시스템 메시지 (JSON) 처리
         *  - PINGPONG: 동일 페이로드로 즉시 응답 (keepalive)
         *  - 구독 응답 (rt_cd=1): 거부됨 → 경고 로그만
         *  - rt_cd=0: 정상 응답 (별도 처리 없음)
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
     * 비동기 재연결 (가상 스레드)
     *
     * 정책:
     *  - 최대 시도 횟수 / 시도 간격 = KisWebSocketProperties
     *  - 연결 성공 시 기존 구독 종목 복원 후 즉시 return
     *  - 모든 시도 실패 시 종료 (다음 외부 구독 요청이 다시 트리거)
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
     * 해당 TR 종류에 활성 구독 종목이 남아있는지 확인 (재연결 트리거 조건)
     */
    private boolean hasActiveSubscriptions(KisRealtimeStreamType type) {
        Set<String> stockCodes = subscriptions.get(type);
        return stockCodes != null && !stockCodes.isEmpty();
    }
}
