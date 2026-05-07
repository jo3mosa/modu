package com.modu.backend.domain.market.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 프론트 실시간 시세 WebSocket 핸들러
 *
 * [처리 대상]
 * - WebSocket 연결 수립
 * - WebSocket 연결 종료
 * - WebSocket 전송 오류
 */
@Slf4j
@RequiredArgsConstructor
public class KisRealtimeFrontendWebSocketHandler extends TextWebSocketHandler {

    static final String STREAM_TYPE_ATTRIBUTE = "streamType";
    static final String STOCK_CODE_ATTRIBUTE = "stockCode";

    private final KisRealtimeSubscriptionManager subscriptionManager;

    /**
     * 프론트 세션 구독 등록
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Object typeAttribute = session.getAttributes().get(STREAM_TYPE_ATTRIBUTE);
        Object stockCodeAttribute = session.getAttributes().get(STOCK_CODE_ATTRIBUTE);

        if (!(typeAttribute instanceof KisRealtimeStreamType type)
                || !(stockCodeAttribute instanceof String stockCode)
                || !stockCode.matches("\\d{6}")) {
            log.warn("Invalid realtime websocket handshake attributes - sessionId: {}, streamType: {}, stockCode: {}",
                    session.getId(), typeAttribute, stockCodeAttribute);
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        subscriptionManager.register(session, new KisRealtimeStreamKey(type, stockCode));
    }

    /**
     * 프론트 세션 구독 해제
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        subscriptionManager.unregister(session);
    }

    /**
     * 프론트 세션 오류 정리
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        subscriptionManager.unregister(session);
    }
}
