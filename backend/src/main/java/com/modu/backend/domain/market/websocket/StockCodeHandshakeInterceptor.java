package com.modu.backend.domain.market.websocket;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;
import java.util.regex.Pattern;

public class StockCodeHandshakeInterceptor implements HandshakeInterceptor {

    private static final Pattern STOCK_CODE_PATTERN = Pattern.compile("\\d{6}");

    private final KisRealtimeStreamType streamType;

    public StockCodeHandshakeInterceptor(KisRealtimeStreamType streamType) {
        this.streamType = streamType;
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

    private String extractStockCode(URI uri) {
        String[] parts = uri.getPath().split("/");
        if (parts.length < 4) {
            return "";
        }
        return parts[3];
    }
}

