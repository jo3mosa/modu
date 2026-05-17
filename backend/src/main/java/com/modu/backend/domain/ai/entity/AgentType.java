package com.modu.backend.domain.ai.entity;

/**
 * AI 에이전트 종류
 *
 * ai_agent (Python) 의 4 봇 페르소나와 1:1 매핑.
 * DB 컬럼 agent_messages.agent CHECK 제약과 동일한 4 종으로 고정.
 *
 *  - BULL     : 강세 리서처 — 매수 시그널/긍정 근거 발화
 *  - BEAR     : 약세 리서처 — 매도 시그널/리스크 근거 발화
 *  - STRATEGY : 전략 매니저 — 전략 제안
 *  - DECIDE   : 의사결정 매니저 — 최종 BUY/SELL/HOLD 결정
 */
public enum AgentType {
    BULL,
    BEAR,
    STRATEGY,
    DECIDE
}
