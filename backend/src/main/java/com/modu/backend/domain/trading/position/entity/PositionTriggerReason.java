package com.modu.backend.domain.trading.position.entity;

/**
 * position_thresholds.triggered_reason 매핑 enum
 *
 * USER_STOP_LOSS    — 사용자 손절가 도달
 * USER_TAKE_PROFIT  — 사용자 익절가 도달
 * AI_STOP_LOSS      — AI 손절가 도달
 * AI_TAKE_PROFIT    — AI 익절가 도달
 *
 * DB CHECK 제약 (CHK_TRIGGERED_REASON) 의 허용 값 집합과 동기화.
 * 동률(active == user == ai) 시 USER_* 우선 — 사용자 의지 명시적 추적.
 */
public enum PositionTriggerReason {
    USER_STOP_LOSS,
    USER_TAKE_PROFIT,
    AI_STOP_LOSS,
    AI_TAKE_PROFIT
}
