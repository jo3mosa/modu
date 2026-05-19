package com.modu.backend.domain.market.feed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modu.backend.domain.market.websocket.KisRealtimeStreamType;
import com.modu.backend.global.config.KisProfiles;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.Topic;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * gateway 전용 — backend pod 들이 publish 한 {@link KisFeedChannels#CONTROL} 메시지 수신.
 *
 * 메시지 → 해당 사용자 세션을 가져오거나 lazy 생성 → 시세/호가 SUBSCRIBE/UNSUBSCRIBE 위임.
 *
 * 체결통보(H0STCNI0)는 부팅 시 자동이라 control 채널로 들어오지 않는다. 시세/호가만 지원.
 */
@Slf4j
@Component
@Profile(KisProfiles.GATEWAY)
@RequiredArgsConstructor
public class KisControlSubscriber implements MessageListener {

    private final RedisMessageListenerContainer container;
    private final UserKisSessionPool sessionPool;
    private final ObjectMapper objectMapper;

    @PostConstruct
    void register() {
        Topic topic = new ChannelTopic(KisFeedChannels.CONTROL);
        container.addMessageListener(this, topic);
        if (!container.isRunning()) {
            container.start();
        }
        log.info("[KisControlSubscriber] subscribed - channel: {}", KisFeedChannels.CONTROL);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            KisControlMessage ctrl = objectMapper.readValue(body, KisControlMessage.class);
            handle(ctrl);
        } catch (Exception e) {
            log.error("[KisControlSubscriber] message handling failed - body: {}", body, e);
        }
    }

    private void handle(KisControlMessage ctrl) {
        UserKisSession session;
        try {
            session = sessionPool.getOrCreate(ctrl.userId());
        } catch (Exception e) {
            log.warn("[KisControlSubscriber] session unavailable - userId: {}, error: {}",
                    ctrl.userId(), e.getMessage());
            return;
        }

        boolean isPrice = KisRealtimeStreamType.PRICE.trId().equals(ctrl.trId());
        boolean isOrderbook = KisRealtimeStreamType.ORDERBOOK.trId().equals(ctrl.trId());
        if (!isPrice && !isOrderbook) {
            log.warn("[KisControlSubscriber] unsupported trId on control channel - trId: {}", ctrl.trId());
            return;
        }

        switch (ctrl.op()) {
            case SUBSCRIBE -> {
                if (isPrice) session.subscribePrice(ctrl.trKey());
                else session.subscribeOrderbook(ctrl.trKey());
            }
            case UNSUBSCRIBE -> {
                if (isPrice) session.unsubscribePrice(ctrl.trKey());
                else session.unsubscribeOrderbook(ctrl.trKey());
            }
        }
    }
}
