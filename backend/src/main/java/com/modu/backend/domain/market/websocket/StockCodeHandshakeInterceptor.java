package com.modu.backend.domain.market.websocket;

import com.modu.backend.domain.auth.jwt.JwtProvider;
import com.modu.backend.domain.market.feed.MarketFeedProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 실시간 시세 WebSocket handshake 인터셉터
 *
 * [검증]
 * - path 내 stockCode 6자리 숫자 형식
 * - query token 으로 사용자 인증 (JWT)
 *   · REMOTE 모드: token 필수 — 없거나 잘못되면 handshake 거부
 *   · LOCAL 모드 : token 없어도 통과 (userId attribute 미설정 → 0L 로 처리)
 *
 * [세션 속성]
 * - streamType, stockCode, userId(인증 성공 시)
 */
@Slf4j
public class StockCodeHandshakeInterceptor implements HandshakeInterceptor {

    private static final Pattern STOCK_CODE_PATTERN = Pattern.compile("\\d{6}");

    private final KisRealtimeStreamType streamType;
    private final JwtProvider jwtProvider;
    private final MarketFeedProperties feedProperties;

    public StockCodeHandshakeInterceptor(
            KisRealtimeStreamType streamType,
            JwtProvider jwtProvider,
            MarketFeedProperties feedProperties
    ) {
        this.streamType = streamType;
        this.jwtProvider = jwtProvider;
        this.feedProperties = feedProperties;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        String stockCode = extractStockCode(request.getURI());
        if (!STOCK_CODE_PATTERN.matcher(stockCode).matches()) {
            return false;
        }

        Long userId = resolveUserId(request.getURI());

        if (userId == null && feedProperties.getClientMode() == MarketFeedProperties.ClientMode.REMOTE) {
            log.warn("[Handshake] REMOTE 모드 — 토큰 누락/잘못됨으로 거부. uri: {}", request.getURI().getPath());
            return false;
        }

        attributes.put(KisRealtimeFrontendWebSocketHandler.STREAM_TYPE_ATTRIBUTE, streamType);
        attributes.put(KisRealtimeFrontendWebSocketHandler.STOCK_CODE_ATTRIBUTE, stockCode);
        if (userId != null) {
            attributes.put(KisRealtimeFrontendWebSocketHandler.USER_ID_ATTRIBUTE, userId);
        }
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception
    ) {
    }

    private String extractStockCode(URI uri) {
        String[] parts = uri.getPath().split("/");
        if (parts.length < 4) {
            return "";
        }
        return parts[3];
    }

    private Long resolveUserId(URI uri) {
        String token = extractQueryParam(uri, "token");
        if (token == null || token.isBlank()) return null;
        try {
            return jwtProvider.getUserIdFromToken(token);
        } catch (Exception e) {
            log.warn("[Handshake] token 검증 실패 - error: {}", e.getMessage());
            return null;
        }
    }

    private String extractQueryParam(URI uri, String name) {
        String query = uri.getQuery();
        if (query == null) return null;
        String prefix = name + "=";
        for (String part : query.split("&")) {
            if (part.startsWith(prefix)) {
                return part.substring(prefix.length());
            }
        }
        return null;
    }
}
