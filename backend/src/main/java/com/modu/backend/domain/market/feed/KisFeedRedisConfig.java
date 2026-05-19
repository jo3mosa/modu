package com.modu.backend.domain.market.feed;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * KIS Market Data Feed 전용 Redis Pub/Sub 인프라.
 *
 * 기본 RedisConnectionFactory 위에 listener container 1개 등록 — backend(REMOTE)/gateway 양쪽 공용.
 * 각 측의 subscriber 가 본 container 에 자신의 채널을 동적으로 add/remove 한다.
 */
@Configuration
public class KisFeedRedisConfig {

    @Bean(name = "kisFeedRedisListenerContainer", destroyMethod = "stop")
    public RedisMessageListenerContainer kisFeedRedisListenerContainer(
            RedisConnectionFactory connectionFactory
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }
}
