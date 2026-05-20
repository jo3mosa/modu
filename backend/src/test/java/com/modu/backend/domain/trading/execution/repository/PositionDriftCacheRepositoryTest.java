package com.modu.backend.domain.trading.execution.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PositionDriftCacheRepository 단위 테스트 (S14P31B106-361).
 *
 * 핵심 회귀 방지:
 *  - Redis 키 포맷 (drift:position:{direction}:{userId}:{stockCode})
 *  - TTL 25h
 *  - 실패 시 throw X — caller (KisVerificationService) 흐름 보호
 */
@ExtendWith(MockitoExtension.class)
class PositionDriftCacheRepositoryTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;

    @InjectMocks PositionDriftCacheRepository repository;

    private static final Long USER_ID = 1L;
    private static final String STOCK = "005930";
    private static final PositionDriftDirection DIRECTION = PositionDriftDirection.KIS_HOLDING_DB_INACTIVE;
    private static final String EXPECTED_KEY = "drift:position:KIS_HOLDING_DB_INACTIVE:1:005930";

    @Test
    @DisplayName("markDetected — 정확한 키로 25h TTL SET 호출")
    void markDetected_setsKeyWithTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        repository.markDetected(USER_ID, STOCK, DIRECTION);

        verify(valueOperations).set(eq(EXPECTED_KEY), eq("1"), eq(Duration.ofHours(25)));
    }

    @Test
    @DisplayName("isAlreadyDetected — Redis hasKey true 면 true")
    void isAlreadyDetected_keyExists_returnsTrue() {
        when(redisTemplate.hasKey(EXPECTED_KEY)).thenReturn(true);

        assertThat(repository.isAlreadyDetected(USER_ID, STOCK, DIRECTION)).isTrue();
    }

    @Test
    @DisplayName("isAlreadyDetected — Redis hasKey false/null 이면 false")
    void isAlreadyDetected_keyMissing_returnsFalse() {
        when(redisTemplate.hasKey(EXPECTED_KEY)).thenReturn(false);
        assertThat(repository.isAlreadyDetected(USER_ID, STOCK, DIRECTION)).isFalse();

        when(redisTemplate.hasKey(EXPECTED_KEY)).thenReturn(null);
        assertThat(repository.isAlreadyDetected(USER_ID, STOCK, DIRECTION)).isFalse();
    }

    @Test
    @DisplayName("clearDetected — DEL 성공 시 true 반환 (회복 로그 트리거용)")
    void clearDetected_deletedReturnsTrue() {
        when(redisTemplate.delete(EXPECTED_KEY)).thenReturn(true);

        assertThat(repository.clearDetected(USER_ID, STOCK, DIRECTION)).isTrue();
    }

    @Test
    @DisplayName("clearDetected — 키 없음 (false/null) 이면 false")
    void clearDetected_keyMissing_returnsFalse() {
        when(redisTemplate.delete(EXPECTED_KEY)).thenReturn(false);
        assertThat(repository.clearDetected(USER_ID, STOCK, DIRECTION)).isFalse();

        when(redisTemplate.delete(EXPECTED_KEY)).thenReturn(null);
        assertThat(repository.clearDetected(USER_ID, STOCK, DIRECTION)).isFalse();
    }

    @Test
    @DisplayName("Redis 예외 시에도 throw X — caller 흐름 보호")
    void redisException_swallowed() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new RuntimeException("redis down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));
        when(redisTemplate.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));
        when(redisTemplate.delete(anyString())).thenThrow(new RuntimeException("redis down"));

        repository.markDetected(USER_ID, STOCK, DIRECTION);
        assertThat(repository.isAlreadyDetected(USER_ID, STOCK, DIRECTION)).isFalse();
        assertThat(repository.clearDetected(USER_ID, STOCK, DIRECTION)).isFalse();
        // 통과하면 성공 (throw X)
    }
}
