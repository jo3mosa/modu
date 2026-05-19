package com.modu.backend.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @Scheduled / @Async 활성화
 *
 * 사용처:
 *  - PositionMonitorScheduler (S14P31B106-302) — 2초 폴링
 *  - PositionIndexKisVerificationService (S14P31B106-268) — 1h 주기 @Async + @Scheduled
 *
 * [Async 추가 근거]
 *  KIS 잔고 검증은 사용자 수 × 150ms 로 길어질 수 있음. 단일 TaskScheduler 스레드 점유 시
 *  PositionMonitor 2초 폴링과 충돌 → @Async 로 별도 스레드 분리.
 */
@Configuration
@EnableScheduling
@EnableAsync
public class SchedulingConfig {
}
