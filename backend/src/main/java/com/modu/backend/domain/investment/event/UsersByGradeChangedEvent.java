package com.modu.backend.domain.investment.event;

/**
 * 투자 성향 프로필 등급 변경 이벤트 — S14P31B106-357
 *
 * StrategyProfileService.updateProfile() 가 발행. AFTER_COMMIT 단계에서
 * UsersByGradeChangedEventListener 가 Redis users:by_grade Set 을 갱신.
 *
 * @param userId         대상 사용자
 * @param prevGradeInt   변경 전 등급 (1~5). 신규 profile 이면 null
 * @param newGradeInt    변경 후 등급 (1~5)
 */
public record UsersByGradeChangedEvent(Long userId, Integer prevGradeInt, int newGradeInt) {
}
