package com.modu.backend.domain.market.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StockCodeHandshakeInterceptorTest {

    @Test
    @DisplayName("6자리 종목코드면 handshake 속성 저장")
    void validStockCodeStoresAttributes() {
        // given
        StockCodeHandshakeInterceptor interceptor = new StockCodeHandshakeInterceptor(KisRealtimeStreamType.PRICE);
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        Map<String, Object> attributes = new HashMap<>();

        when(request.getURI()).thenReturn(URI.create("/ws/stocks/005930/price"));

        // when
        boolean result = interceptor.beforeHandshake(request, null, null, attributes);

        // then
        assertThat(result).isTrue();
        assertThat(attributes.get(KisRealtimeFrontendWebSocketHandler.STREAM_TYPE_ATTRIBUTE))
                .isEqualTo(KisRealtimeStreamType.PRICE);
        assertThat(attributes.get(KisRealtimeFrontendWebSocketHandler.STOCK_CODE_ATTRIBUTE))
                .isEqualTo("005930");
    }

    @Test
    @DisplayName("6자리 숫자가 아니면 handshake 거부")
    void invalidStockCodeRejectsHandshake() {
        // given
        StockCodeHandshakeInterceptor interceptor = new StockCodeHandshakeInterceptor(KisRealtimeStreamType.PRICE);
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        Map<String, Object> attributes = new HashMap<>();

        when(request.getURI()).thenReturn(URI.create("/ws/stocks/samsung/price"));

        // when
        boolean result = interceptor.beforeHandshake(request, null, null, attributes);

        // then
        assertThat(result).isFalse();
        assertThat(attributes).isEmpty();
    }
}

