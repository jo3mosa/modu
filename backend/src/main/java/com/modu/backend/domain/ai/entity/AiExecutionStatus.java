package com.modu.backend.domain.ai.entity;

/**
 * AI 판단 실행 상태 (ai_judgments.execution_status)
 *
 * BE 가 AI 메시지를 받아 자체 판정해서 세팅하는 값. AI 가 내려주는 값이 아님.
 *
 * READY              — BUY/SELL 결정 + 실행 가능, 주문 실행 대기
 * APPROVAL_REQUIRED  — BUY/SELL 결정 + 사용자 리스크 설정과 충돌 → 사용자 승인 대기
 * HOLD_ONLY          — AI 판단이 HOLD, 주문 없이 ai_judgments 기록만
 * BLOCKED            — BUY/SELL 결정 + 실행 불가 (잔고/한도/Kill Switch/자동매매 OFF 등)
 * REJECTED           — 사용자가 명시적으로 승인 거부 (S14P31B106-292)
 * EXPIRED            — 5분 만료 후 스케줄러가 자동 전환 (S14P31B106-292)
 * RECOMMENDED        — 비보유자 BUY 추천 (S14P31B106-354). 자동매매 실행 X,
 *                      사용자가 [승인] 시 매수 실행. APPROVAL_REQUIRED 와 동일 UI 흐름.
 *
 * DB CHECK 제약 (CHK_AI_JUDGMENTS_EXECUTION_STATUS) 의 허용 값 집합과 동기화.
 */
public enum AiExecutionStatus {
    READY,
    APPROVAL_REQUIRED,
    HOLD_ONLY,
    BLOCKED,
    REJECTED,
    EXPIRED,
    RECOMMENDED
}
