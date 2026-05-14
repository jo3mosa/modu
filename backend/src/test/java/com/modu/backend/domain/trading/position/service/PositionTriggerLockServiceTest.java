package com.modu.backend.domain.trading.position.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PositionTriggerLockServiceTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    PositionTriggerLockService lockService;

    private static final String KEY = "lock:position:1:005930";

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lockService = new PositionTriggerLockService(redisTemplate);
    }

    @Test
    @DisplayName("SETNX 성공 시 락 획득")
    void tryLockSuccess() {
        when(valueOps.setIfAbsent(eq(KEY), anyString(), any(Duration.class))).thenReturn(true);

        boolean acquired = lockService.tryLock(1L, "005930", Duration.ofSeconds(30));

        assertThat(acquired).isTrue();
    }

    @Test
    @DisplayName("SETNX 실패 시 락 미획득")
    void tryLockFail() {
        when(valueOps.setIfAbsent(eq(KEY), anyString(), any(Duration.class))).thenReturn(false);

        boolean acquired = lockService.tryLock(1L, "005930", Duration.ofSeconds(30));

        assertThat(acquired).isFalse();
    }

    @Test
    @DisplayName("Redis 예외 발생 시 락 미획득으로 처리 (false 반환)")
    void tryLockExceptionReturnsFalse() {
        when(valueOps.setIfAbsent(eq(KEY), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

        boolean acquired = lockService.tryLock(1L, "005930", Duration.ofSeconds(30));

        assertThat(acquired).isFalse();
    }

    @Test
    @DisplayName("unlock 시 Lua 스크립트로 자기 소유 락만 DEL 실행")
    void unlockExecutesLuaScript() {
        when(valueOps.setIfAbsent(eq(KEY), anyString(), any(Duration.class))).thenReturn(true);
        lockService.tryLock(1L, "005930", Duration.ofSeconds(30));

        lockService.unlock(1L, "005930");

        verify(redisTemplate).execute(any(RedisScript.class), anyList(), anyString());
    }

    @Test
    @DisplayName("락 획득 없이 unlock 호출 시 스크립트 실행 안 함 (보관된 token 없음)")
    void unlockWithoutLockIsNoop() {
        lockService.unlock(1L, "005930");

        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), anyString());
    }

    @Test
    @DisplayName("unlock 두 번 호출하면 첫 호출만 스크립트 실행 (token 1회 소비)")
    void unlockIsIdempotent() {
        when(valueOps.setIfAbsent(eq(KEY), anyString(), any(Duration.class))).thenReturn(true);
        lockService.tryLock(1L, "005930", Duration.ofSeconds(30));

        lockService.unlock(1L, "005930");
        lockService.unlock(1L, "005930");

        verify(redisTemplate, times(1)).execute(any(RedisScript.class), anyList(), anyString());
    }
}
