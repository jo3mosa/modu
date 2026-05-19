package com.modu.backend.domain.market.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modu.backend.global.config.KisProfiles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * gateway 전용 — KIS 에서 받은 시세/호가 파싱 결과를 사용자별 Redis 채널로 publish.
 *
 * publish 실패는 ERROR 로그만 남기고 KIS 수신 흐름은 끊지 않음 (시세는 fire-and-forget).
 */
@Slf4j
@Component
@Profile(KisProfiles.GATEWAY)
@RequiredArgsConstructor
public class KisFeedPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 사용자 채널에 tick envelope publish.
     *
     * @param userId    이 tick 이 도착한 KIS 세션의 소유자 (= 시세를 받을 사용자)
     * @param trId      KIS TR ID (H0STCNT0 / H0STASP0)
     * @param stockCode 종목코드
     * @param payload   파싱된 도메인 객체 (RealtimePriceResponse / OrderbookResponse)
     */
    public void publish(long userId, String trId, String stockCode, Object payload) {
        try {
            JsonNode payloadNode = objectMapper.valueToTree(payload);
            KisTickEnvelope envelope = new KisTickEnvelope(
                    trId, userId, stockCode, payloadNode, System.currentTimeMillis()
            );
            String json = objectMapper.writeValueAsString(envelope);
            redisTemplate.convertAndSend(KisFeedChannels.tickUserChannel(userId), json);
        } catch (Exception e) {
            log.error("[KisFeedPublisher] publish 실패 - userId: {}, trId: {}, stockCode: {}, error: {}",
                    userId, trId, stockCode, e.getMessage());
        }
    }
}
