package com.modu.backend.domain.ai.runner;

import com.modu.backend.domain.ai.service.StockRiskTierSyncService;
import com.modu.backend.domain.ai.service.StockRiskTierSyncService.SyncResult;
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
class StockRiskTierBackfillStartupRunnerTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private StockRiskTierSyncService stockRiskTierSyncService;

    @InjectMocks
    private StockRiskTierBackfillStartupRunner runner;

    @Test
    @DisplayName("run - SCAN 0건이면 syncAll 호출")
    void run_emptyCache_callsSync() {
        givenScanResult(false);
        when(stockRiskTierSyncService.syncAll()).thenReturn(SyncResult.success(10, 100L));

        runner.run(null);

        verify(stockRiskTierSyncService).syncAll();
    }

    @Test
    @DisplayName("run - SCAN 1건 이상이면 syncAll 호출 X (skip)")
    void run_cachePresent_skipsSync() {
        givenScanResult(true);

        runner.run(null);

        verify(stockRiskTierSyncService, never()).syncAll();
    }

    @Test
    @DisplayName("run - syncAll 예외 시에도 throw X")
    void run_syncException_swallowed() {
        givenScanResult(false);
        when(stockRiskTierSyncService.syncAll()).thenThrow(new RuntimeException("boom"));

        runner.run(null);
        // throw 없으면 통과
    }

    @Test
    @DisplayName("run - SCAN 자체 예외 시 backfill 폴백 진행")
    void run_scanException_fallsBackToSync() {
        RedisConnection connection = org.mockito.Mockito.mock(RedisConnection.class);
        RedisKeyCommands keyCommands = org.mockito.Mockito.mock(RedisKeyCommands.class);
        when(connection.keyCommands()).thenReturn(keyCommands);
        when(keyCommands.scan(any(ScanOptions.class))).thenThrow(new RuntimeException("scan err"));
        doAnswer(inv -> {
            RedisCallback<Boolean> cb = inv.getArgument(0);
            return cb.doInRedis(connection);
        }).when(redisTemplate).execute(any(RedisCallback.class));
        when(stockRiskTierSyncService.syncAll()).thenReturn(SyncResult.success(5, 50L));

        runner.run(null);

        verify(stockRiskTierSyncService).syncAll();
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
