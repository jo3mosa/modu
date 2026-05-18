package com.modu.backend.domain.trading.execution.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modu.backend.domain.market.service.KisPlatformWebSocketKeyService;
import com.modu.backend.domain.market.websocket.KisRealtimeStreamType;
import com.modu.backend.domain.trading.execution.cipher.KisExecutionCipher;
import com.modu.backend.domain.trading.execution.parser.ExecutionMessagePayloadParser;
import com.modu.backend.domain.trading.execution.parser.ExecutionPayload;
import com.modu.backend.domain.trading.execution.service.ExecutionDispatchService;
import com.modu.backend.global.config.KisWebSocketProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * KIS 실시간 체결통보 (H0STCNI0) WebSocket 클라이언트 — S14P31B106-291
 *
 * [모델 — 304 시세 클라이언트와 차이]
 *   시세 (KisRealtimeUpstreamClient): TR 종류별 1 세션 + 종목코드 단위 SUBSCRIBE
 *   체결통보 (본 클래스): 단일 세션 + CANO 단위 SUBSCRIBE + AES-256 CBC 복호화 + 우리만 dispatch
 *
 * [SUBSCRIBE 응답에서 IV/Key 수신]
 *   body.output.iv / body.output.key 가 한 세션 내 모든 사용자 (CANO) 의 메시지 복호화에 동일 사용.
 *   여러 사용자 구독해도 cipher 는 세션 단위 1 인스턴스로 충분.
 *   재구독 응답에서 IV/Key 가 갱신되면 최신 값으로 덮어씀.
 *
 * [재연결]
 *   세션 끊김 시 가상 스레드 비동기 재연결 + 기존 CANO 구독 자동 복원.
 *
 * [모의계좌 미지원]
 *   본 시스템은 실 계좌만 제공 → H0STCNI9 SUBSCRIBE 안 함.
 */
@Slf4j
@Component
public class KisExecutionWebSocketClient {

    private static final String SUBSCRIBE   = "1";
    private static final String UNSUBSCRIBE = "2";
    private static final long RECOVERY_COOLDOWN_MS = 30_000L;

    private final ObjectMapper objectMapper;
    private final KisPlatformWebSocketKeyService webSocketKeyService;
    private final KisWebSocketProperties properties;
    private final ExecutionMessagePayloadParser parser;
    private final ExecutionDispatchService dispatchService;

    /** 단일 세션 — 모든 사용자 (CANO) 의 체결통보가 한 세션을 공유 */
    private volatile WebSocketSession session;
    /** 현재 구독 중인 CANO 집합 — 재연결 시 복원 기준 */
    private final Set<String> subscribedCanos = ConcurrentHashMap.newKeySet();
    /** 세션 단위 AES 복호화기 — SUBSCRIBE 응답 시점 초기화 */
    private volatile KisExecutionCipher cipher;
    /** approval_key 복구 cooldown */
    private final AtomicLong lastRecoveryAt = new AtomicLong(0L);

    public KisExecutionWebSocketClient(
            ObjectMapper objectMapper,
            KisPlatformWebSocketKeyService webSocketKeyService,
            KisWebSocketProperties properties,
            ExecutionMessagePayloadParser parser,
            ExecutionDispatchService dispatchService
    ) {
        this.objectMapper = objectMapper;
        this.webSocketKeyService = webSocketKeyService;
        this.properties = properties;
        this.parser = parser;
        this.dispatchService = dispatchService;
    }

    // ───────────────────────────────────────────────────────────────────
    // 외부 API
    // ───────────────────────────────────────────────────────────────────

    /**
     * 사용자 계좌 (CANO 8자리) 체결통보 구독 시작.
     * 본 시스템의 사용자 등록 흐름 후 호출 — 부팅 시 일괄 또는 신규 등록 시 단건.
     */
    public void subscribe(String cano) {
        subscribedCanos.add(cano);
        sendSubscription(cano, SUBSCRIBE);
    }

    /**
     * 구독 해제 (사용자 KIS 자격증명 폐기 등 케이스).
     */
    public void unsubscribe(String cano) {
        subscribedCanos.remove(cano);
        if (session != null && session.isOpen()) {
            sendSubscription(cano, UNSUBSCRIBE);
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // 내부 — SUBSCRIBE 메시지 발신 + 세션 보장
    // ───────────────────────────────────────────────────────────────────

    private void sendSubscription(String cano, String trType) {
        try {
            WebSocketSession active = ensureSession();
            active.sendMessage(new TextMessage(subscriptionMessage(cano, trType)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[ExecutionWS] subscribe interrupted - cano: {}", maskCano(cano));
        } catch (Exception e) {
            log.error("[ExecutionWS] subscribe send failed - cano: {}, error: {}",
                    maskCano(cano), e.getMessage());
        }
    }

    private WebSocketSession ensureSession() throws Exception {
        WebSocketSession current = session;
        if (current != null && current.isOpen()) {
            return current;
        }
        synchronized (this) {
            current = session;
            if (current != null && current.isOpen()) {
                return current;
            }

            CompletableFuture<WebSocketSession> future = new StandardWebSocketClient()
                    .execute(new UpstreamHandler(), properties.getUrl());
            WebSocketSession connected;
            try {
                connected = future.get(properties.getConnectionTimeoutMs(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new Exception("KIS execution WebSocket connection timeout - timeout: "
                        + properties.getConnectionTimeoutMs() + "ms", e);
            }
            session = connected;
            return connected;
        }
    }

    private String subscriptionMessage(String cano, String trType) throws Exception {
        Map<String, Object> message = Map.of(
                "header", Map.of(
                        "approval_key", webSocketKeyService.getApprovalKey(),
                        "custtype", "P",
                        "tr_type", trType,
                        "content-type", "utf-8"
                ),
                "body", Map.of(
                        "input", Map.of(
                                "tr_id", KisRealtimeStreamType.EXECUTION.trId(),
                                "tr_key", cano
                        )
                )
        );
        return objectMapper.writeValueAsString(message);
    }

    private void restoreSubscriptions() {
        for (String cano : subscribedCanos) {
            sendSubscription(cano, SUBSCRIBE);
        }
    }

    /** 계좌번호 마스킹 — 운영 로그용 (앞 2자리만 유지) */
    private static String maskCano(String cano) {
        if (cano == null || cano.length() <= 2) return "******";
        return cano.substring(0, 2) + "*".repeat(cano.length() - 2);
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
            log.warn("[ExecutionWS] connection closed - status: {}", status);
            if (session == s) {
                session = null;
            }
            // 활성 구독이 있으면 가상 스레드로 비동기 재연결
            if (!subscribedCanos.isEmpty()) {
                Thread.ofVirtual().start(KisExecutionWebSocketClient.this::reconnect);
            }
        }

        @Override
        public void handleTransportError(WebSocketSession s, Throwable exception) {
            log.error("[ExecutionWS] transport error", exception);
        }

        /**
         * 시스템 메시지 (JSON) — PINGPONG / SUBSCRIBE 응답 (IV/Key 수신).
         */
        private void handleSystemMessage(WebSocketSession s, String payload) throws Exception {
            JsonNode root = objectMapper.readTree(payload);
            String trId = root.path("header").path("tr_id").asText();
            if ("PINGPONG".equals(trId)) {
                s.sendMessage(new TextMessage(payload));
                return;
            }

            String rtCd = root.path("body").path("rt_cd").asText();
            if ("1".equals(rtCd)) {
                String msg = root.path("body").path("msg1").asText();
                log.warn("[ExecutionWS] subscribe rejected - trId: {}, message: {}", trId, msg);
                if (msg.contains("invalid approval") && tryAcquireRecovery()) {
                    recoverInvalidApproval();
                }
                return;
            }

            // SUBSCRIBE 성공 응답 — output.iv / output.key 로 cipher 초기화
            JsonNode output = root.path("body").path("output");
            String iv  = output.path("iv").asText(null);
            String key = output.path("key").asText(null);
            if (iv != null && key != null && !iv.isBlank() && !key.isBlank()) {
                cipher = new KisExecutionCipher(key, iv);
                log.info("[ExecutionWS] cipher initialized from SUBSCRIBE response - trId: {}", trId);
            }
        }

        /**
         * 데이터 메시지 — `|` 4분할 → 암호화 페이로드 추출 → 복호화 → 파서 → dispatch.
         */
        private void handleDataMessage(String raw) {
            KisExecutionCipher activeCipher = cipher;
            if (activeCipher == null) {
                log.warn("[ExecutionWS] cipher 미초기화 — 메시지 무시. SUBSCRIBE 응답 누락 가능");
                return;
            }
            Optional<String> encPayload = parser.extractDataPart(raw);
            if (encPayload.isEmpty()) {
                log.warn("[ExecutionWS] 메시지 형식 오류 — 무시");
                return;
            }
            String plaintext;
            try {
                plaintext = activeCipher.decrypt(encPayload.get());
            } catch (Exception e) {
                log.error("[ExecutionWS] 복호화 실패 — 메시지 무시", e);
                return;
            }
            for (ExecutionPayload payload : parser.parseFilledOnly(plaintext)) {
                try {
                    dispatchService.dispatch(payload);
                } catch (Exception e) {
                    log.error("[ExecutionWS] dispatch 실패 - kisOrderNo: {}", payload.kisOrderNo(), e);
                }
            }
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
                restoreSubscriptions();
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("[ExecutionWS] reconnect failed - attempt: {}, error: {}",
                        attempt, e.getMessage());
            }
        }
    }

    private boolean tryAcquireRecovery() {
        long now = System.currentTimeMillis();
        long prev = lastRecoveryAt.get();
        if (now - prev < RECOVERY_COOLDOWN_MS) return false;
        return lastRecoveryAt.compareAndSet(prev, now);
    }

    private void recoverInvalidApproval() {
        log.warn("[ExecutionWS] approval_key 복구 시작");
        webSocketKeyService.evictApprovalKey();
        Thread.ofVirtual().start(this::restoreSubscriptions);
    }
}
