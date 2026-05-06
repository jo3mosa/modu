package com.modu.backend.domain.market.websocket;

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
 *
 * [세션 속성]
 * - streamType: 체결가/호가 구분
 * - stockCode: KIS tr_key 구독 대상 종목코드
 */
public class StockCodeHandshakeInterceptor implements HandshakeInterceptor {

    private static final Pattern STOCK_CODE_PATTERN = Pattern.compile("\\d{6}");

    private final KisRealtimeStreamType streamType;

    public StockCodeHandshakeInterceptor(KisRealtimeStreamType streamType) {
        this.streamType = streamType;
    }

    /**
     * handshake 전 종목코드 검증 및 세션 속성 저장
     */
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

        attributes.put(KisRealtimeFrontendWebSocketHandler.STREAM_TYPE_ATTRIBUTE, streamType);
        attributes.put(KisRealtimeFrontendWebSocketHandler.STOCK_CODE_ATTRIBUTE, stockCode);
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

    /**
     * path 내 stockCode 추출
     */
    private String extractStockCode(URI uri) {
        String[] parts = uri.getPath().split("/");
        if (parts.length < 4) {
            return "";
        }
        return parts[3];
    }
}
