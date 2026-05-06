package com.modu.backend.domain.market.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@RequiredArgsConstructor
public class KisRealtimeFrontendWebSocketHandler extends TextWebSocketHandler {

    static final String STREAM_TYPE_ATTRIBUTE = "streamType";
    static final String STOCK_CODE_ATTRIBUTE = "stockCode";

    private final KisRealtimeSubscriptionManager subscriptionManager;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        KisRealtimeStreamType type = (KisRealtimeStreamType) session.getAttributes().get(STREAM_TYPE_ATTRIBUTE);
        String stockCode = (String) session.getAttributes().get(STOCK_CODE_ATTRIBUTE);
        subscriptionManager.register(session, new KisRealtimeStreamKey(type, stockCode));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        subscriptionManager.unregister(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        subscriptionManager.unregister(session);
    }
}

