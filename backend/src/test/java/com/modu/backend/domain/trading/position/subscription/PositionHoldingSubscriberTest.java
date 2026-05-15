package com.modu.backend.domain.trading.position.subscription;

import com.modu.backend.domain.market.websocket.KisRealtimeStreamKey;
import com.modu.backend.domain.market.websocket.KisRealtimeStreamType;
import com.modu.backend.domain.market.websocket.KisRealtimeSubscriptionManager;
import com.modu.backend.domain.trading.position.repository.PositionThresholdRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PositionHoldingSubscriberTest {

    @Mock PositionThresholdRepository positionThresholdRepository;
    @Mock KisRealtimeSubscriptionManager subscriptionManager;

    @InjectMocks
    PositionHoldingSubscriber subscriber;

    @Test
    @DisplayName("부팅 시 활성 보유 종목을 PRICE 스트림으로 일괄 구독")
    void subscribeAllOnStartup() {
        Set<String> stockCodes = new LinkedHashSet<>(List.of("005930", "035720"));
        when(positionThresholdRepository.findActiveStockCodes()).thenReturn(stockCodes);

        subscriber.subscribeAllOnStartup();

        ArgumentCaptor<KisRealtimeStreamKey> captor = ArgumentCaptor.forClass(KisRealtimeStreamKey.class);
        verify(subscriptionManager, org.mockito.Mockito.times(2)).registerServerSide(captor.capture());

        List<KisRealtimeStreamKey> keys = captor.getAllValues();
        assertThat(keys).extracting(KisRealtimeStreamKey::stockCode).containsExactlyInAnyOrder("005930", "035720");
        assertThat(keys).extracting(KisRealtimeStreamKey::type)
                .allMatch(t -> t == KisRealtimeStreamType.PRICE);
    }

    @Test
    @DisplayName("활성 종목 없으면 구독 호출 없음")
    void noSubscriptionsWhenEmpty() {
        when(positionThresholdRepository.findActiveStockCodes()).thenReturn(Set.of());

        subscriber.subscribeAllOnStartup();

        verify(subscriptionManager, never()).registerServerSide(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("개별 구독 실패가 다음 종목 구독을 막지 않음")
    void singleFailureDoesNotStop() {
        Set<String> stockCodes = new LinkedHashSet<>(List.of("005930", "035720"));
        when(positionThresholdRepository.findActiveStockCodes()).thenReturn(stockCodes);
        org.mockito.Mockito.doThrow(new RuntimeException("subscribe failed"))
                .doNothing()
                .when(subscriptionManager).registerServerSide(org.mockito.ArgumentMatchers.any());

        subscriber.subscribeAllOnStartup();

        verify(subscriptionManager, org.mockito.Mockito.times(2))
                .registerServerSide(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("onBuyFilled 호출 시 PRICE 스트림 server-side 등록")
    void onBuyFilledRegisters() {
        subscriber.onBuyFilled("005930");

        verify(subscriptionManager).registerServerSide(
                new KisRealtimeStreamKey(KisRealtimeStreamType.PRICE, "005930"));
    }

    @Test
    @DisplayName("onFullySold 호출 시 PRICE 스트림 server-side 해제")
    void onFullySoldUnregisters() {
        subscriber.onFullySold("005930");

        verify(subscriptionManager).unregisterServerSide(
                new KisRealtimeStreamKey(KisRealtimeStreamType.PRICE, "005930"));
    }

    @Test
    @DisplayName("stockCode null/blank 시 무시")
    void ignoreBlankStockCode() {
        subscriber.onBuyFilled(null);
        subscriber.onBuyFilled("  ");
        subscriber.onFullySold(null);
        subscriber.onFullySold("");

        verify(subscriptionManager, never()).registerServerSide(org.mockito.ArgumentMatchers.any());
        verify(subscriptionManager, never()).unregisterServerSide(org.mockito.ArgumentMatchers.any());
    }
}
