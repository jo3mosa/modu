package com.modu.backend.domain.investment.runner;

import com.modu.backend.domain.investment.service.UsersByGradeBackfillService;
import com.modu.backend.domain.investment.service.UsersByGradeBackfillService.BackfillResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsersByGradeBackfillStartupRunnerTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock UsersByGradeBackfillService backfillService;
    @InjectMocks UsersByGradeBackfillStartupRunner runner;

    @Test
    @DisplayName("run - SCAN 0건이면 backfillAll 호출")
    void run_emptyCache_callsBackfill() {
        givenScanResult(false);
        when(backfillService.backfillAll()).thenReturn(BackfillResult.success(10, 100L));

        runner.run(null);

        verify(backfillService).backfillAll();
    }

    @Test
    @DisplayName("run - SCAN 1건 이상이면 backfill skip")
    void run_cachePresent_skipsBackfill() {
        givenScanResult(true);

        runner.run(null);

        verify(backfillService, never()).backfillAll();
    }

    @Test
    @DisplayName("run - backfillAll 예외 시에도 throw X")
    void run_backfillException_swallowed() {
        givenScanResult(false);
        when(backfillService.backfillAll()).thenThrow(new RuntimeException("boom"));

        runner.run(null);
    }

    @Test
    @DisplayName("run - SCAN 콜백 내부 예외 시 backfill 폴백 진행")
    void run_scanException_fallsBackToBackfill() {
        RedisConnection connection = org.mockito.Mockito.mock(RedisConnection.class);
        RedisKeyCommands keyCommands = org.mockito.Mockito.mock(RedisKeyCommands.class);
        when(connection.keyCommands()).thenReturn(keyCommands);
        when(keyCommands.scan(any(ScanOptions.class))).thenThrow(new RuntimeException("scan err"));
        doAnswer(inv -> {
            RedisCallback<Boolean> cb = inv.getArgument(0);
            return cb.doInRedis(connection);
        }).when(redisTemplate).execute(any(RedisCallback.class));
        when(backfillService.backfillAll()).thenReturn(BackfillResult.success(5, 50L));

        runner.run(null);

        verify(backfillService).backfillAll();
    }

    @Test
    @DisplayName("run - redisTemplate.execute 자체 예외 시 backfill 폴백 진행")
    void run_executeException_fallsBackToBackfill() {
        when(redisTemplate.execute(any(RedisCallback.class)))
                .thenThrow(new RuntimeException("execute err"));
        when(backfillService.backfillAll()).thenReturn(BackfillResult.success(5, 50L));

        runner.run(null);

        verify(backfillService).backfillAll();
    }

    @Test
    @DisplayName("run - backfillAll 실패 result 시에도 throw X")
    void run_failedResult_logsError() {
        givenScanResult(false);
        when(backfillService.backfillAll()).thenReturn(BackfillResult.failed("redis err"));

        runner.run(null);

        verify(backfillService).backfillAll();
    }

    @SuppressWarnings("unchecked")
    private void givenScanResult(boolean hasNext) {
        RedisConnection connection = org.mockito.Mockito.mock(RedisConnection.class);
        RedisKeyCommands keyCommands = org.mockito.Mockito.mock(RedisKeyCommands.class);
        Cursor<byte[]> cursor = org.mockito.Mockito.mock(Cursor.class);
        when(connection.keyCommands()).thenReturn(keyCommands);
        when(keyCommands.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(hasNext);
        doAnswer(inv -> {
            RedisCallback<Boolean> cb = inv.getArgument(0);
            return cb.doInRedis(connection);
        }).when(redisTemplate).execute(any(RedisCallback.class));
    }
}
