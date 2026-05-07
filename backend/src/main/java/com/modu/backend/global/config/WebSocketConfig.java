package com.modu.backend.global.config;

import com.modu.backend.domain.market.websocket.KisRealtimeFrontendWebSocketHandler;
import com.modu.backend.domain.market.websocket.KisRealtimeStreamType;
import com.modu.backend.domain.market.websocket.KisRealtimeSubscriptionManager;
import com.modu.backend.domain.market.websocket.StockCodeHandshakeInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 프론트 WebSocket 엔드포인트 설정
 *
 * [엔드포인트]
 * - /ws/stocks/{stockCode}/price: 실시간 체결가(H0STCNT0)
 * - /ws/stocks/{stockCode}/orderbook: 실시간 호가(H0STASP0)
 *
 * [방식]
 * - STOMP 미사용
 * - raw WebSocket 기반 중계
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final KisRealtimeSubscriptionManager subscriptionManager;

    private final KisWebSocketProperties properties;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        KisRealtimeFrontendWebSocketHandler handler = new KisRealtimeFrontendWebSocketHandler(subscriptionManager);
        // 허용 Origin은 환경변수(KIS_WEBSOCKET_ALLOWED_ORIGINS)로 관리
        // 로컬: * / 운영: https://moduinvestment.co.kr,https://k14b106.p.ssafy.io
        String[] origins = properties.getAllowedOrigins().split(",");

        registry.addHandler(handler, "/ws/stocks/{stockCode}/price")
                .addInterceptors(new StockCodeHandshakeInterceptor(KisRealtimeStreamType.PRICE))
                .setAllowedOriginPatterns(origins);

        registry.addHandler(handler, "/ws/stocks/{stockCode}/orderbook")
                .addInterceptors(new StockCodeHandshakeInterceptor(KisRealtimeStreamType.ORDERBOOK))
                .setAllowedOriginPatterns(origins);
    }
}
