package com.modu.backend.domain.strategy.service;

import com.modu.backend.domain.strategy.entity.AutoTradeSettings;
import com.modu.backend.domain.strategy.entity.AutoTradeStatus;
import com.modu.backend.domain.strategy.repository.AutoTradeSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KillSwitchServiceTest {

    @Mock AutoTradeSettingsRepository autoTradeSettingsRepository;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    @InjectMocks
    KillSwitchService service;

    private static final Long USER_ID = 1L;
    private static final String STOCK = "005930";
    private static final String COUNT_KEY = "kis-reject-count:1:005930";

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName("recordReject 1~4회: 카운트만 증가 (Lua INCR+EXPIRE 단일 호출), Kill Switch 미발동")
    void recordRejectBelowThreshold() {
        AutoTradeSettings settings = AutoTradeSettings.builder()
                .userId(USER_ID).autoTradeStatus(AutoTradeStatus.ACTIVE).build();
        when(redisTemplate.<Long>execute(any(RedisScript.class), eq(List.of(COUNT_KEY)), anyString()))
                .thenReturn(3L);
        when(autoTradeSettingsRepository.findById(USER_ID)).thenReturn(Optional.of(settings));

        service.recordReject(USER_ID, STOCK, "테스트 거부");

        assertThat(settings.getAutoTradeStatus()).isEqualTo(AutoTradeStatus.ACTIVE);
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), anyString());
    }

    @Test
    @DisplayName("recordReject 5회 도달: Kill Switch 발동 + 카운트 DEL + Redis 상태 SET")
    void recordRejectTriggersKillSwitch() {
        AutoTradeSettings settings = AutoTradeSettings.builder()
                .userId(USER_ID).autoTradeStatus(AutoTradeStatus.ACTIVE).build();
        when(redisTemplate.<Long>execute(any(RedisScript.class), eq(List.of(COUNT_KEY)), anyString()))
                .thenReturn(5L);
        when(autoTradeSettingsRepository.findById(USER_ID)).thenReturn(Optional.of(settings));

        service.recordReject(USER_ID, STOCK, "한국투자증권 API 토큰 발급에 실패했습니다.");

        assertThat(settings.getAutoTradeStatus()).isEqualTo(AutoTradeStatus.KILL_SWITCHED);
        assertThat(settings.getKillSwitchReason()).contains("005930");
        assertThat(settings.getKillSwitchTriggeredAt()).isNotNull();
        verify(redisTemplate).delete(COUNT_KEY);
        verify(valueOps).set(eq("auto-trade:status:1"), eq("KILL_SWITCHED"));
    }

    @Test
    @DisplayName("recordReject 5회 도달했으나 이미 KILL_SWITCHED 상태: 멱등 (DB 변경 X, Redis 상태 SET 안 함)")
    void recordRejectAlreadyKillSwitched() {
        AutoTradeSettings settings = AutoTradeSettings.builder()
                .userId(USER_ID).autoTradeStatus(AutoTradeStatus.KILL_SWITCHED).build();
        settings.triggerKillSwitch("previous");
        when(redisTemplate.<Long>execute(any(RedisScript.class), eq(List.of(COUNT_KEY)), anyString()))
                .thenReturn(5L);
        when(autoTradeSettingsRepository.findById(USER_ID)).thenReturn(Optional.of(settings));

        service.recordReject(USER_ID, STOCK, "또 다른 거부");

        verify(valueOps, never()).set(eq("auto-trade:status:1"), anyString());
        verify(redisTemplate).delete(COUNT_KEY);
    }

    @Test
    @DisplayName("recordReject 5회 도달 + AutoTradeSettings row 없음: 무시 (DB/Redis 변경 X)")
    void recordRejectNoSettingsRow() {
        when(redisTemplate.<Long>execute(any(RedisScript.class), eq(List.of(COUNT_KEY)), anyString()))
                .thenReturn(5L);
        when(autoTradeSettingsRepository.findById(USER_ID)).thenReturn(Optional.empty());

        service.recordReject(USER_ID, STOCK, "test");

        verify(valueOps, never()).set(eq("auto-trade:status:1"), anyString());
    }

    @Test
    @DisplayName("recordSuccess: 카운트 DEL")
    void recordSuccessDelsCount() {
        service.recordSuccess(USER_ID, STOCK);

        verify(redisTemplate).delete(COUNT_KEY);
    }

    @Test
    @DisplayName("recordReject null 입력: 무시")
    void recordRejectNullSafe() {
        service.recordReject(null, STOCK, "test");
        service.recordReject(USER_ID, null, "test");

        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), anyString());
    }
}
