package com.modu.backend.domain.trading.position.service;

import com.modu.backend.domain.trading.position.entity.PositionThreshold;
import com.modu.backend.domain.trading.position.entity.PositionTriggerReason;
import com.modu.backend.domain.trading.position.repository.PositionThresholdRepository;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PositionMonitorServiceTest {

    @Mock PositionThresholdRepository positionThresholdRepository;
    @Mock PositionTriggerLockService lockService;
    @Mock PositionTriggerExecutor triggerExecutor;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    @InjectMocks
    PositionMonitorService service;

    private static final Long USER_ID = 1L;
    private static final String STOCK = "005930";
    private static final String PRICE_KEY = "market:price:" + STOCK;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(lockService.tryLock(any(), anyString(), any(Duration.class))).thenReturn(true);
    }

    @Test
    @DisplayName("손절가 도달 + active==user → USER_STOP_LOSS 트리거")
    void userStopLossTrigger() {
        PositionThreshold p = position(70000L, null, 70000L, null);
        when(positionThresholdRepository.findAllActiveForMonitor()).thenReturn(List.of(p));
        when(valueOps.get(PRICE_KEY)).thenReturn("69500");

        service.evaluateAll();

        verify(triggerExecutor).execute(eq(p.getId()), eq(PositionTriggerReason.USER_STOP_LOSS));
    }

    @Test
    @DisplayName("손절가 도달 + active!=user → AI_STOP_LOSS 트리거 (AI 가 더 보수적)")
    void aiStopLossTrigger() {
        // active=68000 (ai), user=65000 → ai 가 더 높아 더 빨리 발동
        PositionThreshold p = position(68000L, null, 65000L, null);
        when(positionThresholdRepository.findAllActiveForMonitor()).thenReturn(List.of(p));
        when(valueOps.get(PRICE_KEY)).thenReturn("67900");

        service.evaluateAll();

        verify(triggerExecutor).execute(eq(p.getId()), eq(PositionTriggerReason.AI_STOP_LOSS));
    }

    @Test
    @DisplayName("익절가 도달 + active==user → USER_TAKE_PROFIT 트리거")
    void userTakeProfitTrigger() {
        PositionThreshold p = position(null, 77000L, null, 77000L);
        when(positionThresholdRepository.findAllActiveForMonitor()).thenReturn(List.of(p));
        when(valueOps.get(PRICE_KEY)).thenReturn("77000");

        service.evaluateAll();

        verify(triggerExecutor).execute(eq(p.getId()), eq(PositionTriggerReason.USER_TAKE_PROFIT));
    }

    @Test
    @DisplayName("익절가 도달 + active!=user → AI_TAKE_PROFIT 트리거")
    void aiTakeProfitTrigger() {
        // active=75000 (ai), user=80000 → ai 가 더 낮아 더 빨리 발동
        PositionThreshold p = position(null, 75000L, null, 80000L);
        when(positionThresholdRepository.findAllActiveForMonitor()).thenReturn(List.of(p));
        when(valueOps.get(PRICE_KEY)).thenReturn("75100");

        service.evaluateAll();

        verify(triggerExecutor).execute(eq(p.getId()), eq(PositionTriggerReason.AI_TAKE_PROFIT));
    }

    @Test
    @DisplayName("손절/익절 둘 다 컬럼 존재 + 손절 도달 시 손절 우선")
    void stopLossPriorityOverTakeProfit() {
        // 손절 70000, 익절 77000. 현재가 70000 → 손절만 트리거
        PositionThreshold p = position(70000L, 77000L, 70000L, 77000L);
        when(positionThresholdRepository.findAllActiveForMonitor()).thenReturn(List.of(p));
        when(valueOps.get(PRICE_KEY)).thenReturn("70000");

        service.evaluateAll();

        verify(triggerExecutor).execute(eq(p.getId()), eq(PositionTriggerReason.USER_STOP_LOSS));
    }

    @Test
    @DisplayName("조건 미충족 시 트리거 발동 안 함")
    void noTriggerWhenInsideRange() {
        PositionThreshold p = position(70000L, 77000L, 70000L, 77000L);
        when(positionThresholdRepository.findAllActiveForMonitor()).thenReturn(List.of(p));
        when(valueOps.get(PRICE_KEY)).thenReturn("73000");

        service.evaluateAll();

        verify(triggerExecutor, never()).execute(any(), any());
    }

    @Test
    @DisplayName("Redis 시세 null 이면 트리거 발동 안 함")
    void noTriggerWhenPriceMissing() {
        PositionThreshold p = position(70000L, null, 70000L, null);
        when(positionThresholdRepository.findAllActiveForMonitor()).thenReturn(List.of(p));
        when(valueOps.get(PRICE_KEY)).thenReturn(null);

        service.evaluateAll();

        verify(triggerExecutor, never()).execute(any(), any());
    }

    @Test
    @DisplayName("락 획득 실패 시 평가 자체를 건너뜀")
    void skipWhenLockNotAcquired() {
        PositionThreshold p = position(70000L, null, 70000L, null);
        when(positionThresholdRepository.findAllActiveForMonitor()).thenReturn(List.of(p));
        when(lockService.tryLock(any(), anyString(), any(Duration.class))).thenReturn(false);

        service.evaluateAll();

        verify(valueOps, never()).get(anyString());
        verify(triggerExecutor, never()).execute(any(), any());
    }

    @Test
    @DisplayName("실행 중 예외가 나도 락은 해제된다")
    void unlockEvenOnException() {
        PositionThreshold p = position(70000L, null, 70000L, null);
        when(positionThresholdRepository.findAllActiveForMonitor()).thenReturn(List.of(p));
        when(valueOps.get(PRICE_KEY)).thenReturn("69000");
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(triggerExecutor).execute(any(), any());

        service.evaluateAll();

        verify(lockService).unlock(eq(USER_ID), eq(STOCK));
    }

    @Test
    @DisplayName("Redis 시세가 숫자가 아니면 트리거 발동 안 함")
    void noTriggerWhenPriceMalformed() {
        PositionThreshold p = position(70000L, null, 70000L, null);
        when(positionThresholdRepository.findAllActiveForMonitor()).thenReturn(List.of(p));
        when(valueOps.get(PRICE_KEY)).thenReturn("not-a-number");

        service.evaluateAll();

        verify(triggerExecutor, never()).execute(any(), any());
    }

    /**
     * 테스트용 PositionThreshold 빌더 헬퍼
     */
    private PositionThreshold position(Long activeStop, Long activeTarget, Long userStop, Long userTakeProfit) {
        PositionThreshold p = PositionThreshold.builder()
                .userId(USER_ID)
                .stockCode(STOCK)
                .sourceOrderId(100L)
                .quantity(10L)
                .avgEntryPrice(70000L)
                .activeStopLossPrice(activeStop)
                .activeTargetPrice(activeTarget)
                .userStopLossPrice(userStop)
                .userTakeProfitPrice(userTakeProfit)
                .build();
        ReflectionTestUtils.setField(p, "id", 1L);
        return p;
    }
}
