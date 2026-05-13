package com.modu.backend.domain.trading.entity;

/**
 * 주문 출처 (orders.source)
 *
 * MANUAL       — 사용자 직접 주문
 * AI_DECISION  — AI 자동매매 판단 주문
 * STOP_LOSS    — 손절 임계 도달 자동 주문
 * TAKE_PROFIT  — 익절 임계 도달 자동 주문
 *
 * HistorySourceFilter.AUTO 는 AI_DECISION / STOP_LOSS / TAKE_PROFIT 를 모두 포함 (사용자 입장 "자동 매매")
 */
public enum OrderSource {
    MANUAL, AI_DECISION, STOP_LOSS, TAKE_PROFIT
}
