package com.modu.backend.global.config;

import com.modu.backend.domain.market.websocket.KisRealtimeFrontendWebSocketHandler;
import com.modu.backend.domain.market.websocket.KisRealtimeStreamType;
import com.modu.backend.domain.market.websocket.KisRealtimeSubscriptionManager;
import com.modu.backend.domain.market.websocket.StockCodeHandshakeInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@Profile(KisProfiles.NOT_GATEWAY)
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final KisRealtimeSubscriptionManager subscriptionManager;
    private final KisWebSocketProperties kisWebSocketProperties;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        KisRealtimeFrontendWebSocketHandler handler = new KisRealtimeFrontendWebSocketHandler(subscriptionManager);
        String[] allowedOriginPatterns = kisWebSocketProperties.getAllowedOriginPatterns().toArray(String[]::new);

        registry.addHandler(handler, "/ws/stocks/{stockCode}/price")
                .addInterceptors(new StockCodeHandshakeInterceptor(KisRealtimeStreamType.PRICE))
                .setAllowedOriginPatterns(allowedOriginPatterns);

        registry.addHandler(handler, "/ws/stocks/{stockCode}/orderbook")
                .addInterceptors(new StockCodeHandshakeInterceptor(KisRealtimeStreamType.ORDERBOOK))
                .setAllowedOriginPatterns(allowedOriginPatterns);
    }
}
