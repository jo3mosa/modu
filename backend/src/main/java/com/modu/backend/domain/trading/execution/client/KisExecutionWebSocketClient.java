package com.modu.backend.domain.trading.execution.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modu.backend.domain.market.websocket.KisRealtimeStreamType;
import com.modu.backend.domain.trading.execution.cipher.KisExecutionCipher;
import com.modu.backend.domain.trading.execution.parser.ExecutionMessagePayloadParser;
import com.modu.backend.domain.trading.execution.parser.ExecutionPayload;
import com.modu.backend.domain.trading.execution.service.ExecutionDispatchService;
import com.modu.backend.domain.user.entity.KisCredential;
import com.modu.backend.domain.user.repository.KisCredentialRepository;
import com.modu.backend.domain.user.service.KisTokenService;
import com.modu.backend.global.config.KisWebSocketProperties;
import com.modu.backend.global.util.AesGcmEncryptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * KIS 실시간 체결통보 (H0STCNI0) WebSocket 클라이언트 — S14P31B106-291
 *
 * [세션 모델 — 사용자별]
 *   KIS 정책: appkey 당 동시 WS 세션 1개. 체결통보는 SUBSCRIBE 의 tr_key 로
 *   해당 계정 소유자의 HTS ID 를 요구하며, approval_key 도 그 계정 appkey 로 발급해야 한다.
 *   따라서 사용자별로 자신의 자격증명으로 1 세션을 따로 연다.
 *   (시세 PRICE/ORDERBOOK 은 공용 platform 키 + 단일 세션 multiplex 로 KisRealtimeUpstreamClient 가 담당)
 *
 * [부팅 자동 구독]
 *   ApplicationReadyEvent 시점에 HTS ID 가 등록된 모든 사용자 → 자동 SUBSCRIBE.
 *
 * [SUBSCRIBE 응답 → cipher 초기화]
 *   사용자별 세션에서 받은 SUBSCRIBE 응답의 output.iv / output.key 로 그 사용자 전용 cipher 구성.
 *
 * [모의계좌 미지원]
 *   본 시스템은 실 계좌만 제공 → H0STCNI9 미지원.
 */
@Slf4j
@Component
public class KisExecutionWebSocketClient {

    private static final String SUBSCRIBE = "1";
    private static final String UNSUBSCRIBE = "2";

    private final KisCredentialRepository credentialRepository;
    private final KisTokenService kisTokenService;
    private final AesGcmEncryptor encryptor;
    private final KisWebSocketProperties properties;
    private final ObjectMapper objectMapper;
    private final ExecutionMessagePayloadParser parser;
    private final ExecutionDispatchService dispatchService;

    /** 사용자별 WebSocket 세션 */
    private final Map<Long, WebSocketSession> sessions = new ConcurrentHashMap<>();
    /** 사용자별 AES 복호화기 — SUBSCRIBE 응답 시점 초기화 */
    private final Map<Long, KisExecutionCipher> ciphers = new ConcurrentHashMap<>();

    public KisExecutionWebSocketClient(
            KisCredentialRepository credentialRepository,
            KisTokenService kisTokenService,
            AesGcmEncryptor encryptor,
            KisWebSocketProperties properties,
            ObjectMapper objectMapper,
            ExecutionMessagePayloadParser parser,
            ExecutionDispatchService dispatchService
    ) {
        this.credentialRepository = credentialRepository;
        this.kisTokenService = kisTokenService;
        this.encryptor = encryptor;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.parser = parser;
        this.dispatchService = dispatchService;
    }

    // ───────────────────────────────────────────────────────────────────
    // 부팅 자동 구독 + 외부 API
    // ───────────────────────────────────────────────────────────────────

    @EventListener(ApplicationReadyEvent.class)
    void subscribeAllOnStartup() {
        List<KisCredential> credentials = credentialRepository.findByHtsIdEncIsNotNull();
        if (credentials.isEmpty()) {
            log.info("[ExecutionWS] 부팅 자동 구독 대상 없음 (HTS ID 등록 사용자 0명)");
            return;
        }
        log.info("[ExecutionWS] 부팅 자동 구독 시작 - 사용자 수: {}", credentials.size());
        for (KisCredential cred : credentials) {
            try {
                subscribe(cred.getUserId());
            } catch (Exception e) {
                log.warn("[ExecutionWS] 부팅 구독 실패 - userId: {}, error: {}", cred.getUserId(), e.getMessage());
            }
        }
    }

    public void subscribe(Long userId) {
        sendSubscription(userId, SUBSCRIBE);
    }

    public void unsubscribe(Long userId) {
        sendSubscription(userId, UNSUBSCRIBE);
        WebSocketSession s = sessions.remove(userId);
        if (s != null) {
            try { s.close(); } catch (Exception ignored) {}
        }
        ciphers.remove(userId);
    }

    // ───────────────────────────────────────────────────────────────────
    // 내부 — 사용자별 SUBSCRIBE
    // ───────────────────────────────────────────────────────────────────

    private void sendSubscription(Long userId, String trType) {
        try {
            KisCredential cred = credentialRepository.findByUserId(userId).orElse(null);
            if (cred == null || cred.getHtsIdEnc() == null) {
                log.warn("[ExecutionWS] subscribe skip - userId: {} (자격증명 또는 HTS ID 없음)", userId);
                return;
            }
            String appKey = encryptor.decrypt(cred.getAppKeyEnc());
            String appSecret = encryptor.decrypt(cred.getAppSecretEnc());
            String htsId = encryptor.decrypt(cred.getHtsIdEnc());
            String approvalKey = kisTokenService.getOrIssueWebSocketKey(userId, appKey, appSecret);

            WebSocketSession session = ensureSession(userId);
            String payload = buildPayload(approvalKey, htsId, trType);
            log.warn("[DEBUG-WIRE] EXEC SUBSCRIBE send - userId: {}, htsId: {}, payload: {}",
                    userId, maskHts(htsId), payload);
            synchronized (session) {
                session.sendMessage(new TextMessage(payload));
            }
        } catch (Exception e) {
            log.error("[ExecutionWS] subscribe send failed - userId: {}, error: {}", userId, e.getMessage());
        }
    }

    private WebSocketSession ensureSession(Long userId) throws Exception {
        WebSocketSession current = sessions.get(userId);
        if (current != null && current.isOpen()) return current;
        synchronized (this) {
            current = sessions.get(userId);
            if (current != null && current.isOpen()) return current;

            WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
            headers.setOrigin("http://localhost");
            CompletableFuture<WebSocketSession> future = new StandardWebSocketClient()
                    .execute(new UpstreamHandler(userId), headers, URI.create(properties.getUrl()));
            WebSocketSession connected;
            try {
                connected = future.get(properties.getConnectionTimeoutMs(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new Exception("KIS execution WebSocket connection timeout - userId: " + userId, e);
            }
            sessions.put(userId, connected);
            return connected;
        }
    }

    private String buildPayload(String approvalKey, String htsId, String trType) throws Exception {
        Map<String, Object> message = Map.of(
                "header", Map.of(
                        "approval_key", approvalKey,
                        "custtype", "P",
                        "tr_type", trType,
                        "content-type", "utf-8"
                ),
                "body", Map.of(
                        "input", Map.of(
                                "tr_id", KisRealtimeStreamType.EXECUTION.trId(),
                                "tr_key", htsId
                        )
                )
        );
        return objectMapper.writeValueAsString(message);
    }

    private static String maskHts(String htsId) {
        if (htsId == null || htsId.length() <= 2) return "***";
        return htsId.substring(0, 2) + "*".repeat(htsId.length() - 2);
    }

    // ───────────────────────────────────────────────────────────────────
    // WebSocket 핸들러 (사용자별)
    // ───────────────────────────────────────────────────────────────────

    private final class UpstreamHandler extends TextWebSocketHandler {

        private final Long userId;

        private UpstreamHandler(Long userId) {
            this.userId = userId;
        }

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
            log.warn("[ExecutionWS] connection closed - userId: {}, status: {}", userId, status);
            sessions.remove(userId, s);
            // 재연결 — 가상 스레드로 SUBSCRIBE 재발신 (세션 자동 생성)
            Thread.ofVirtual().start(() -> reconnect(userId));
        }

        @Override
        public void handleTransportError(WebSocketSession s, Throwable exception) {
            log.error("[ExecutionWS] transport error - userId: {}", userId, exception);
        }

        private void handleSystemMessage(WebSocketSession s, String payload) throws Exception {
            log.warn("[DEBUG-WIRE] EXEC system message recv - userId: {}, payload: {}", userId, payload);
            JsonNode root = objectMapper.readTree(payload);
            String trId = root.path("header").path("tr_id").asText();
            if ("PINGPONG".equals(trId)) {
                synchronized (s) {
                    s.sendMessage(new TextMessage(payload));
                }
                return;
            }

            String rtCd = root.path("body").path("rt_cd").asText();
            if ("1".equals(rtCd) || "9".equals(rtCd)) {
                log.warn("[ExecutionWS] subscribe rejected - userId: {}, message: {}",
                        userId, root.path("body").path("msg1").asText());
                return;
            }

            // SUBSCRIBE 성공 응답 — output.iv / output.key 로 cipher 초기화
            JsonNode output = root.path("body").path("output");
            String iv  = output.path("iv").asText(null);
            String key = output.path("key").asText(null);
            if (iv != null && key != null && !iv.isBlank() && !key.isBlank()) {
                ciphers.put(userId, new KisExecutionCipher(key, iv));
                log.info("[ExecutionWS] cipher initialized - userId: {}", userId);
            }
        }

        private void handleDataMessage(String raw) {
            KisExecutionCipher cipher = ciphers.get(userId);
            if (cipher == null) {
                log.warn("[ExecutionWS] cipher 미초기화 - userId: {} — 메시지 무시", userId);
                return;
            }
            Optional<String> encPayload = parser.extractDataPart(raw);
            if (encPayload.isEmpty()) {
                log.warn("[ExecutionWS] 메시지 형식 오류 - userId: {}", userId);
                return;
            }
            String plaintext;
            try {
                plaintext = cipher.decrypt(encPayload.get());
            } catch (Exception e) {
                log.error("[ExecutionWS] 복호화 실패 - userId: {}", userId, e);
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

    private void reconnect(Long userId) {
        for (int attempt = 1; attempt <= properties.getReconnectMaxAttempts(); attempt++) {
            try {
                Thread.sleep(properties.getReconnectDelayMs());
                sendSubscription(userId, SUBSCRIBE);
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("[ExecutionWS] reconnect failed - userId: {}, attempt: {}, error: {}",
                        userId, attempt, e.getMessage());
            }
        }
    }
}
