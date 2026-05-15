package com.modu.backend.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @Scheduled 활성화
 *
 * 사용처:
 *  - PositionMonitorScheduler (S14P31B106-302) — 2초 폴링
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
