package com.modu.backend.domain.market.websocket;

import com.modu.backend.domain.auth.jwt.JwtProvider;
import com.modu.backend.domain.market.feed.MarketFeedProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.server.ServerHttpRequest;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StockCodeHandshakeInterceptorTest {

    @Mock JwtProvider jwtProvider;
    @Mock MarketFeedProperties feedProperties;

    private StockCodeHandshakeInterceptor interceptor;

    @BeforeEach
    void setUp() {
        // LOCAL 모드 — token 없어도 handshake 통과 (REMOTE 면 토큰 누락 시 reject)
        when(feedProperties.getClientMode()).thenReturn(MarketFeedProperties.ClientMode.LOCAL);
        interceptor = new StockCodeHandshakeInterceptor(
                KisRealtimeStreamType.PRICE, jwtProvider, feedProperties);
    }

    @Test
    @DisplayName("6자리 종목코드면 handshake 속성 저장")
    void validStockCodeStoresAttributes() {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        Map<String, Object> attributes = new HashMap<>();

        when(request.getURI()).thenReturn(URI.create("/ws/stocks/005930/price"));

        boolean result = interceptor.beforeHandshake(request, null, null, attributes);

        assertThat(result).isTrue();
        assertThat(attributes.get(KisRealtimeFrontendWebSocketHandler.STREAM_TYPE_ATTRIBUTE))
                .isEqualTo(KisRealtimeStreamType.PRICE);
        assertThat(attributes.get(KisRealtimeFrontendWebSocketHandler.STOCK_CODE_ATTRIBUTE))
                .isEqualTo("005930");
    }

    @Test
    @DisplayName("6자리 숫자가 아니면 handshake 거부")
    void invalidStockCodeRejectsHandshake() {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        Map<String, Object> attributes = new HashMap<>();

        when(request.getURI()).thenReturn(URI.create("/ws/stocks/samsung/price"));

        boolean result = interceptor.beforeHandshake(request, null, null, attributes);

        assertThat(result).isFalse();
        assertThat(attributes).isEmpty();
    }
}
