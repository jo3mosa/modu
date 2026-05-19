package com.modu.backend.domain.ai.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockRiskTierRedisRepositoryTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private StockRiskTierRedisRepository repository;

    @BeforeEach
    void setUp() {
        // lenient — save/get 만 사용. saveBatch 는 executePipelined 만 호출.
    }

    @Test
    @DisplayName("save - 정상 tier 는 SET 호출")
    void save_validTier_callsSet() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        repository.save("005930", 3);

        verify(valueOperations).set("stock:risk_tier:005930", "3");
    }

    @Test
    @DisplayName("save - tier 0 은 early return")
    void save_tierZero_earlyReturn() {
        repository.save("005930", 0);

        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("save - tier 6 은 early return")
    void save_tierSix_earlyReturn() {
        repository.save("005930", 6);

        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("save - Redis 예외 시에도 throw 하지 않음")
    void save_redisException_swallowed() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new RuntimeException("redis down")).when(valueOperations).set(anyString(), anyString());

        repository.save("005930", 3);
        // 예외 propagation 없음 — 통과하면 성공
    }

    @Test
    @DisplayName("saveBatch - 빈 입력은 pipeline 호출 안 함")
    void saveBatch_empty_skipsPipeline() {
        repository.saveBatch(Map.of());
        repository.saveBatch(null);

        verify(redisTemplate, never()).executePipelined(any(RedisCallback.class));
    }

    @Test
    @DisplayName("saveBatch - 정상 입력은 pipeline 호출")
    void saveBatch_validInput_callsPipeline() {
        repository.saveBatch(Map.of("005930", 3, "000660", 5));

        verify(redisTemplate, times(1)).executePipelined(any(RedisCallback.class));
    }

    @Test
    @DisplayName("saveBatch - Redis 예외 시에도 throw 하지 않음")
    void saveBatch_redisException_swallowed() {
        when(redisTemplate.executePipelined(any(RedisCallback.class)))
                .thenThrow(new RuntimeException("redis down"));

        repository.saveBatch(Map.of("005930", 3));
        // 예외 propagation 없음
    }

    @Test
    @DisplayName("get - 미설정 키는 null 반환")
    void get_missingKey_returnsNull() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("stock:risk_tier:005930")).thenReturn(null);

        assertThat(repository.get("005930")).isNull();
    }

    @Test
    @DisplayName("get - 정상 값은 int 로 파싱")
    void get_validValue_returnsInt() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("stock:risk_tier:005930")).thenReturn("3");

        assertThat(repository.get("005930")).isEqualTo(3);
    }

    @Test
    @DisplayName("get - Redis 예외 시 null 반환")
    void get_redisException_returnsNull() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("redis down"));

        assertThat(repository.get("005930")).isNull();
    }
}
