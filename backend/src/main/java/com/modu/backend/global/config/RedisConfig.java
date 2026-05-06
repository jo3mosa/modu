package com.modu.backend.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;

/**
 * Redis 캐시 설정
 *
 * [캐시 TTL 전략]
 * - stock:price       → 3초  (실시간 시세 데이터)
 * - kis:platform:token → 23시간 (플랫폼 KIS 토큰)
 *
 * [Fail-open 전략]
 * - Redis 장애/타임아웃 시 캐시 예외를 흡수하고 원본 데이터 조회로 폴백
 * - 캐시는 성능 계층이므로 Redis 장애가 서비스 장애로 전파되지 않도록 설계
 */
@Slf4j
@EnableCaching
@Configuration
public class RedisConfig implements CachingConfigurer {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new GenericJackson2JsonRedisSerializer()))
                .disableCachingNullValues();

        // 캐시별 TTL 설정
        Map<String, RedisCacheConfiguration> cacheConfigurations = Map.of(
                "stock:price",          defaultConfig.entryTtl(Duration.ofSeconds(3)),
                "kis:platform:token",   defaultConfig.entryTtl(Duration.ofHours(23))
        );

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .build();
    }

    /**
     * Redis 장애 시 fail-open 전략
     * - 캐시 예외를 흡수하고 원본 메서드 실행으로 폴백
     * - 장애 내용은 ERROR 로그로 기록
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new RedisCacheErrorHandler();
    }

    @Slf4j
    static class RedisCacheErrorHandler implements CacheErrorHandler {

        @Override
        public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
            log.error("Redis 캐시 조회 실패 - cache: {}, key: {}, error: {}",
                    cache.getName(), key, e.getMessage());
        }

        @Override
        public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
            log.error("Redis 캐시 저장 실패 - cache: {}, key: {}, error: {}",
                    cache.getName(), key, e.getMessage());
        }

        @Override
        public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
            log.error("Redis 캐시 삭제 실패 - cache: {}, key: {}, error: {}",
                    cache.getName(), key, e.getMessage());
        }

        @Override
        public void handleCacheClearError(RuntimeException e, Cache cache) {
            log.error("Redis 캐시 전체 삭제 실패 - cache: {}, error: {}",
                    cache.getName(), e.getMessage());
        }
    }
}
