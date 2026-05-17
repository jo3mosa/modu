package com.modu.backend.domain.ai.scheduler;

import com.modu.backend.domain.ai.service.PendingJudgmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * AI 판단 승인 대기 5분 만료 스케줄러 (S14P31B106-292)
 *
 * [주기]
 *  fixedDelay = 60_000ms (1분). 직전 사이클 종료 후 1분 대기 → 다음 사이클.
 *
 * [동작]
 *  APPROVAL_REQUIRED + approval_expires_at < NOW() 인 row 를 EXPIRED 로 전환.
 *  partial index (IDX_AI_JUDGMENTS_APPROVAL_EXPIRES) 활용으로 폴링 부담 최소화.
 *
 * [로컬 디버깅 토글]
 *  modu.pending-judgment-expiry.enabled=false 로 비활성화 가능 (기본 true).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "modu.pending-judgment-expiry.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class PendingJudgmentExpirySweeper {

    private final PendingJudgmentService pendingJudgmentService;

    @Scheduled(fixedDelay = 60_000L)
    public void runCycle() {
        try {
            pendingJudgmentService.expirePending();
        } catch (Exception e) {
            log.error("AI 판단 만료 스케줄러 사이클 실패", e);
        }
    }
}
