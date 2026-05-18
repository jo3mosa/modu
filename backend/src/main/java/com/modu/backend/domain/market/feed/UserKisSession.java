package com.modu.backend.domain.market.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modu.backend.domain.market.cache.RealtimePriceCacheService;
import com.modu.backend.domain.market.dto.RealtimePriceResponse;
import com.modu.backend.domain.market.websocket.KisRealtimeMessageParser;
import com.modu.backend.domain.market.websocket.KisRealtimeStreamKey;
import com.modu.backend.domain.market.websocket.KisRealtimeStreamType;
import com.modu.backend.domain.trading.execution.cipher.KisExecutionCipher;
import com.modu.backend.domain.trading.execution.parser.ExecutionMessagePayloadParser;
import com.modu.backend.domain.trading.execution.parser.ExecutionPayload;
import com.modu.backend.domain.trading.execution.service.ExecutionDispatchService;
import com.modu.backend.global.config.KisWebSocketProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 한 사용자의 KIS WebSocket 세션 — 시세(H0STCNT0) / 호가(H0STASP0) / 체결통보(H0STCNI0) multiplex.
 *
 * [세션 모델]
 *   사용자 자격증명(appKey/appSecret)으로 KIS WS 1세션을 보유. KIS 의 appkey 당 1세션 제약 +
 *   41건 등록 한도가 모두 사용자별로 적용 — 사용자 간 격리.
 *
 * [지원 TR]
 *   - 시세 (H0STCNT0) : subscribePrice / unsubscribePrice
 *   - 호가 (H0STASP0) : subscribeOrderbook / unsubscribeOrderbook
 *   - 체결통보 (H0STCNI0) : start() 시 HTS ID 있으면 자동 SUBSCRIBE (사용자가 keep-alive)
 *
 * [재연결]
 *   세션 close 시 가상 스레드로 비동기 재연결 시도 + 체결통보 / 활성 시세 구독 복원.
 *   close() 호출 후엔 재연결 X.
 *
 * [한도 정책]
 *   현재는 단순 size 체크 후 ERROR 로그. 향후 LRU eviction 도입 가능 (followups).
 */
@Slf4j
public class UserKisSession {

    private static final String SUBSCRIBE = "1";
    private static final String UNSUBSCRIBE = "2";

    /** KIS WebSocket 한 세션당 등록 한도 (체결가/호가/체결통보 모두 합산). */
    private static final int KIS_SUBSCRIPTION_LIMIT = 41;

    // ── 사용자 자격증명 ──────────────────────────────────────────────────────
    private final long userId;
    private final String htsId;        // 체결통보 SUBSCRIBE 의 tr_key. null 이면 체결통보 미가입.
    private final String approvalKey;  // KIS WS approval_key (사용자 키로 발급). 발급은 Factory 책임.

    // ── 외부 의존성 ─────────────────────────────────────────────────────────
    private final KisWebSocketProperties properties;
    private final ObjectMapper objectMapper;
    private final KisRealtimeMessageParser priceParser;
    private final ExecutionMessagePayloadParser executionParser;
    private final ExecutionDispatchService executionDispatch;
    private final KisFeedPublisher feedPublisher;
    private final RealtimePriceCacheService priceCache;

    // ── 세션 상태 ────────────────────────────────────────────────────────────
    private volatile WebSocketSession wsSession;
    /** 체결통보 SUBSCRIBE 응답에서 받은 AES key/iv 로 초기화 */
    private volatile KisExecutionCipher executionCipher;
    /** 활성 시세/호가 구독 (PRICE / ORDERBOOK 만) */
    private final Set<KisRealtimeStreamKey> activeMarketSubs = ConcurrentHashMap.newKeySet();
    /** 체결통보 SUBSCRIBE 발신 여부 */
    private final AtomicBoolean executionSubscribed = new AtomicBoolean(false);
    /** 재연결 단일 실행 가드 */
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    /** close() 호출 후 재연결 차단 플래그 */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public UserKisSession(
            long userId,
            String htsId,
            String approvalKey,
            KisWebSocketProperties properties,
            ObjectMapper objectMapper,
            KisRealtimeMessageParser priceParser,
            ExecutionMessagePayloadParser executionParser,
            ExecutionDispatchService executionDispatch,
            KisFeedPublisher feedPublisher,
            RealtimePriceCacheService priceCache
    ) {
        this.userId = userId;
        this.htsId = htsId;
        this.approvalKey = approvalKey;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.priceParser = priceParser;
        this.executionParser = executionParser;
        this.executionDispatch = executionDispatch;
        this.feedPublisher = feedPublisher;
        this.priceCache = priceCache;
    }

    public long userId() {
        return userId;
    }

    // ── 외부 API ────────────────────────────────────────────────────────────

    /**
     * 세션 시작 — KIS WS 연결 + 체결통보 SUBSCRIBE (HTS ID 있을 때).
     * 시세/호가 SUBSCRIBE 는 호출자가 subscribePrice / subscribeOrderbook 으로 별도 발신.
     */
    public synchronized void start() throws Exception {
        if (closed.get()) {
            throw new IllegalStateException("Session already closed - userId: " + userId);
        }
        ensureSession();
        if (htsId != null && !htsId.isBlank() && executionSubscribed.compareAndSet(false, true)) {
            sendSubscription(KisRealtimeStreamType.EXECUTION.trId(), htsId, SUBSCRIBE);
        }
    }

    public void subscribePrice(String stockCode) {
        subscribeMarket(KisRealtimeStreamType.PRICE, stockCode);
    }

    public void unsubscribePrice(String stockCode) {
        unsubscribeMarket(KisRealtimeStreamType.PRICE, stockCode);
    }

    public void subscribeOrderbook(String stockCode) {
        subscribeMarket(KisRealtimeStreamType.ORDERBOOK, stockCode);
    }

    public void unsubscribeOrderbook(String stockCode) {
        unsubscribeMarket(KisRealtimeStreamType.ORDERBOOK, stockCode);
    }

    /** 세션 close — 이후 재연결 차단 + WS close. */
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        WebSocketSession s = wsSession;
        wsSession = null;
        if (s != null) {
            try { s.close(); } catch (Exception ignored) {}
        }
        activeMarketSubs.clear();
        executionSubscribed.set(false);
        executionCipher = null;
    }

    // ── 내부 — 시세/호가 SUBSCRIBE 공통 ─────────────────────────────────────

    private void subscribeMarket(KisRealtimeStreamType type, String stockCode) {
        KisRealtimeStreamKey key = new KisRealtimeStreamKey(type, stockCode);
        if (!activeMarketSubs.add(key)) {
            // 이미 구독 중 — 중복 SUBSCRIBE 회피
            return;
        }
        if (currentSubscriptionCount() > KIS_SUBSCRIPTION_LIMIT) {
            activeMarketSubs.remove(key);
            log.error("[UserKisSession] subscription limit exceeded - userId: {}, current: {}, attempted: {} {}",
                    userId, currentSubscriptionCount(), type.trId(), stockCode);
            return;
        }
        sendSubscription(type.trId(), stockCode, SUBSCRIBE);
    }

    private void unsubscribeMarket(KisRealtimeStreamType type, String stockCode) {
        KisRealtimeStreamKey key = new KisRealtimeStreamKey(type, stockCode);
        if (!activeMarketSubs.remove(key)) {
            return;
        }
        WebSocketSession s = wsSession;
        if (s != null && s.isOpen()) {
            sendSubscription(type.trId(), stockCode, UNSUBSCRIBE);
        }
    }

    /** 체결통보 1건 + 활성 시세/호가 합산 */
    private int currentSubscriptionCount() {
        return activeMarketSubs.size() + (executionSubscribed.get() ? 1 : 0);
    }

    // ── 내부 — WebSocket 송수신 ─────────────────────────────────────────────

    private void sendSubscription(String trId, String trKey, String trType) {
        try {
            WebSocketSession session = ensureSession();
            String payload = buildPayload(trId, trKey, trType);
            synchronized (session) {
                session.sendMessage(new TextMessage(payload));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[UserKisSession] subscription send interrupted - userId: {}, trId: {}, trKey: {}",
                    userId, trId, trKey);
        } catch (Exception e) {
            log.error("[UserKisSession] subscription send failed - userId: {}, trId: {}, trKey: {}, error: {}",
                    userId, trId, trKey, e.getMessage());
        }
    }

    private WebSocketSession ensureSession() throws Exception {
        WebSocketSession current = wsSession;
        if (current != null && current.isOpen()) return current;

        synchronized (this) {
            current = wsSession;
            if (current != null && current.isOpen()) return current;
            if (closed.get()) {
                throw new IllegalStateException("Session closed - userId: " + userId);
            }

            WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
            headers.setOrigin("http://localhost");
            CompletableFuture<WebSocketSession> future = new StandardWebSocketClient()
                    .execute(new Handler(), headers, URI.create(properties.getUrl()));
            WebSocketSession connected;
            try {
                connected = future.get(properties.getConnectionTimeoutMs(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new Exception("KIS WS connection timeout - userId: " + userId, e);
            }
            wsSession = connected;
            return connected;
        }
    }

    private String buildPayload(String trId, String trKey, String trType) throws Exception {
        Map<String, Object> message = Map.of(
                "header", Map.of(
                        "approval_key", approvalKey,
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

    private void restoreSubscriptions() {
        // 체결통보 먼저 (cipher 필요)
        if (htsId != null && !htsId.isBlank()) {
            sendSubscription(KisRealtimeStreamType.EXECUTION.trId(), htsId, SUBSCRIBE);
        }
        // 시세/호가
        for (KisRealtimeStreamKey key : activeMarketSubs) {
            sendSubscription(key.type().trId(), key.stockCode(), SUBSCRIBE);
        }
    }

    private void scheduleReconnect() {
        if (closed.get()) return;
        if (!reconnecting.compareAndSet(false, true)) {
            log.info("[UserKisSession] reconnect already in progress - userId: {}", userId);
            return;
        }
        Thread.ofVirtual().start(() -> {
            try {
                reconnectLoop();
            } finally {
                reconnecting.set(false);
            }
        });
    }

    private void reconnectLoop() {
        for (int attempt = 1; attempt <= properties.getReconnectMaxAttempts(); attempt++) {
            if (closed.get()) return;
            try {
                Thread.sleep(properties.getReconnectDelayMs());
                ensureSession();
                restoreSubscriptions();
                log.info("[UserKisSession] reconnected - userId: {}, attempt: {}", userId, attempt);
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("[UserKisSession] reconnect failed - userId: {}, attempt: {}, error: {}",
                        userId, attempt, e.getMessage());
            }
        }
        log.error("[UserKisSession] reconnect 한도 소진 - userId: {}", userId);
    }

    // ── WebSocket 핸들러 ─────────────────────────────────────────────────────

    private final class Handler extends TextWebSocketHandler {

        @Override
        public void handleTextMessage(WebSocketSession s, TextMessage message) {
            String payload = message.getPayload();
            try {
                if (payload.startsWith("{")) {
                    handleSystemMessage(payload);
                } else {
                    handleDataMessage(payload);
                }
            } catch (Exception e) {
                log.error("[UserKisSession] handle message failed - userId: {}", userId, e);
            }
        }

        @Override
        public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
            log.warn("[UserKisSession] connection closed - userId: {}, status: {}", userId, status);
            if (wsSession == s) wsSession = null;
            scheduleReconnect();
        }

        @Override
        public void handleTransportError(WebSocketSession s, Throwable exception) {
            log.error("[UserKisSession] transport error - userId: {}", userId, exception);
        }

        private void handleSystemMessage(String payload) throws Exception {
            JsonNode root = objectMapper.readTree(payload);
            String trId = root.path("header").path("tr_id").asText();

            if ("PINGPONG".equals(trId)) {
                WebSocketSession s = wsSession;
                if (s != null) {
                    synchronized (s) {
                        s.sendMessage(new TextMessage(payload));
                    }
                }
                return;
            }

            String rtCd = root.path("body").path("rt_cd").asText();
            if ("1".equals(rtCd) || "9".equals(rtCd)) {
                log.warn("[UserKisSession] subscribe rejected - userId: {}, trId: {}, msg: {}",
                        userId, trId, root.path("body").path("msg1").asText());
                return;
            }

            // 체결통보 SUBSCRIBE 응답 → cipher 초기화
            if (KisRealtimeStreamType.EXECUTION.trId().equals(trId)) {
                JsonNode output = root.path("body").path("output");
                String iv = output.path("iv").asText(null);
                String key = output.path("key").asText(null);
                if (iv != null && key != null && !iv.isBlank() && !key.isBlank()) {
                    executionCipher = new KisExecutionCipher(key, iv);
                    log.info("[UserKisSession] execution cipher initialized - userId: {}", userId);
                }
            }
        }

        private void handleDataMessage(String raw) {
            // "0|TR_ID|cnt|data..." 또는 "1|TR_ID|..." — TR_ID 추출 후 분기
            int firstPipe = raw.indexOf('|');
            int secondPipe = firstPipe < 0 ? -1 : raw.indexOf('|', firstPipe + 1);
            if (secondPipe < 0) {
                log.warn("[UserKisSession] data message format invalid - userId: {}", userId);
                return;
            }
            String trId = raw.substring(firstPipe + 1, secondPipe);

            if (KisRealtimeStreamType.EXECUTION.trId().equals(trId)) {
                handleExecutionMessage(raw);
            } else {
                handleMarketMessage(raw);
            }
        }

        private void handleMarketMessage(String raw) {
            priceParser.parse(raw).ifPresent(parsed -> {
                feedPublisher.publish(userId, parsed.key().type().trId(), parsed.key().stockCode(), parsed.payload());
                if (parsed.payload() instanceof RealtimePriceResponse price) {
                    priceCache.cache(price);
                }
            });
        }

        private void handleExecutionMessage(String raw) {
            KisExecutionCipher cipher = executionCipher;
            if (cipher == null) {
                log.warn("[UserKisSession] cipher 미초기화 — execution msg drop. userId: {}", userId);
                return;
            }
            Optional<String> encPayload = executionParser.extractDataPart(raw);
            if (encPayload.isEmpty()) {
                log.warn("[UserKisSession] execution msg format invalid - userId: {}", userId);
                return;
            }
            String plaintext;
            try {
                plaintext = cipher.decrypt(encPayload.get());
            } catch (Exception e) {
                log.error("[UserKisSession] execution decrypt failed - userId: {}", userId, e);
                return;
            }
            for (ExecutionPayload payload : executionParser.parseFilledOnly(plaintext)) {
                try {
                    executionDispatch.dispatch(payload);
                } catch (Exception e) {
                    log.error("[UserKisSession] execution dispatch failed - kisOrderNo: {}", payload.kisOrderNo(), e);
                }
            }
        }
    }
}
