package com.modu.backend.domain.market.cache;

import com.modu.backend.domain.market.dto.RealtimePriceResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

/**
 * RealtimePriceCacheService 단위 테스트
 *
 * RedisTemplate mock 기반 — 실제 Redis IO 는 검증하지 않음.
 * 검증 범위: 키/값 매핑, null 입력 무시, 예외 흡수.
 */
@ExtendWith(MockitoExtension.class)
class RealtimePriceCacheServiceTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    RealtimePriceCacheService cacheService;

    @BeforeEach
    void setUp() {
        cacheService = new RealtimePriceCacheService(redisTemplate);
    }

    @Test
    @DisplayName("정상 — market:price:{stockCode} 키에 currentPrice 문자열 SET")
    void 정상_SET() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        RealtimePriceResponse price = priceOf("005930", 71000L);

        cacheService.cache(price);

        verify(valueOps).set("market:price:005930", "71000");
    }

    @Test
    @DisplayName("null 입력 — SET 호출 안 됨")
    void null_입력_무시() {
        cacheService.cache(null);

        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("stockCode 가 null — SET 호출 안 됨")
    void stockCode_null_무시() {
        RealtimePriceResponse price = priceOf(null, 71000L);

        cacheService.cache(price);

        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("currentPrice 가 null — SET 호출 안 됨")
    void currentPrice_null_무시() {
        RealtimePriceResponse price = priceOf("005930", null);

        cacheService.cache(price);

        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("Redis 예외 발생 — ERROR 로그만, 예외 전파 안 됨")
    void Redis_예외_흡수() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        doThrow(new RedisConnectionFailureException("connection refused"))
                .when(valueOps).set(any(), any());

        // 예외가 호출자로 전파되지 않아야 함
        cacheService.cache(priceOf("005930", 71000L));

        verify(valueOps).set("market:price:005930", "71000");
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private RealtimePriceResponse priceOf(String stockCode, Long currentPrice) {
        return new RealtimePriceResponse(
                stockCode, "120000", currentPrice,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null
        );
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
