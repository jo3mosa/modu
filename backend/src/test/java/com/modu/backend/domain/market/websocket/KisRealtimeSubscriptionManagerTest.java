package com.modu.backend.domain.market.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KisRealtimeSubscriptionManagerTest {

    @Mock KisRealtimeUpstreamClient upstreamClient;
    @Mock WebSocketSession firstSession;
    @Mock WebSocketSession secondSession;

    @Test
    @DisplayName("첫 구독자 등록 시 KIS subscribe 호출")
    void subscribeOnFirstSession() {
        // given
        KisRealtimeSubscriptionManager manager = new KisRealtimeSubscriptionManager(new ObjectMapper(), upstreamClient);
        KisRealtimeStreamKey key = new KisRealtimeStreamKey(KisRealtimeStreamType.PRICE, "005930");
        when(firstSession.getId()).thenReturn("session-1");

        // when
        manager.register(firstSession, key);

        // then
        verify(upstreamClient).subscribe(key);
    }

    @Test
    @DisplayName("동일 구독 추가 등록 시 KIS subscribe 중복 호출 없음")
    void doNotSubscribeAgainForSameKey() {
        // given
        KisRealtimeSubscriptionManager manager = new KisRealtimeSubscriptionManager(new ObjectMapper(), upstreamClient);
        KisRealtimeStreamKey key = new KisRealtimeStreamKey(KisRealtimeStreamType.PRICE, "005930");
        when(firstSession.getId()).thenReturn("session-1");
        when(secondSession.getId()).thenReturn("session-2");

        // when
        manager.register(firstSession, key);
        manager.register(secondSession, key);

        // then
        verify(upstreamClient).subscribe(key);
    }

    @Test
    @DisplayName("마지막 구독자 종료 시 KIS unsubscribe 호출")
    void unsubscribeOnLastSession() {
        // given
        KisRealtimeSubscriptionManager manager = new KisRealtimeSubscriptionManager(new ObjectMapper(), upstreamClient);
        KisRealtimeStreamKey key = new KisRealtimeStreamKey(KisRealtimeStreamType.PRICE, "005930");
        when(firstSession.getId()).thenReturn("session-1");
        when(secondSession.getId()).thenReturn("session-2");

        manager.register(firstSession, key);
        manager.register(secondSession, key);

        // when
        manager.unregister(firstSession);
        manager.unregister(secondSession);

        // then
        verify(upstreamClient).unsubscribe(key);
    }

    @Test
    @DisplayName("마지막 구독자가 아니면 KIS unsubscribe 호출 없음")
    void doNotUnsubscribeWhenOtherSessionExists() {
        // given
        KisRealtimeSubscriptionManager manager = new KisRealtimeSubscriptionManager(new ObjectMapper(), upstreamClient);
        KisRealtimeStreamKey key = new KisRealtimeStreamKey(KisRealtimeStreamType.PRICE, "005930");
        when(firstSession.getId()).thenReturn("session-1");
        when(secondSession.getId()).thenReturn("session-2");

        manager.register(firstSession, key);
        manager.register(secondSession, key);

        // when
        manager.unregister(firstSession);

        // then
        verify(upstreamClient, never()).unsubscribe(key);
    }

    @Test
    @DisplayName("동일 구독 세션에 실시간 데이터 fan-out")
    void broadcastToSubscribedSessions() throws Exception {
        // given
        KisRealtimeSubscriptionManager manager = new KisRealtimeSubscriptionManager(new ObjectMapper(), upstreamClient);
        KisRealtimeStreamKey key = new KisRealtimeStreamKey(KisRealtimeStreamType.PRICE, "005930");
        RealtimePayload payload = new RealtimePayload("005930", 71200L);

        when(firstSession.getId()).thenReturn("session-1");
        when(firstSession.isOpen()).thenReturn(true);

        manager.register(firstSession, key);

        // when
        manager.broadcast(key, payload);

        // then
        verify(firstSession).sendMessage(any(TextMessage.class));
    }

    private record RealtimePayload(
            String stockCode,
            Long currentPrice
    ) {
    }
}

