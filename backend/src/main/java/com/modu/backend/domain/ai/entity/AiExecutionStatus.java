package com.modu.backend.domain.ai.entity;

/**
 * AI 판단 실행 상태 (ai_judgments.execution_status)
 *
 * BE 가 AI 메시지를 받아 자체 판정해서 세팅하는 값. AI 가 내려주는 값이 아님.
 *
 * READY              — BUY/SELL 결정 + 실행 가능, 주문 실행 대기
 * APPROVAL_REQUIRED  — BUY/SELL 결정 + 사용자 리스크 설정과 충돌 → 사용자 승인 대기
 *                      (판정 로직은 S14P31B106-292 에서 확정 — 현 PR 에선 enum/컬럼만 정의)
 * HOLD_ONLY          — AI 판단이 HOLD, 주문 없이 ai_judgments 기록만
 * BLOCKED            — BUY/SELL 결정 + 실행 불가 (잔고/한도/시장 마감 등)
 *
 * DB CHECK 제약 (CHK_AI_JUDGMENTS_EXECUTION_STATUS) 의 허용 값 집합과 동기화.
 */
public enum AiExecutionStatus {
    READY,
    APPROVAL_REQUIRED,
    HOLD_ONLY,
    BLOCKED
}
