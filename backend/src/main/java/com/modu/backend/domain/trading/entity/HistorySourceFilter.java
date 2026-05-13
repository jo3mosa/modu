package com.modu.backend.domain.trading.entity;

/**
 * 거래 이력 조회 source 필터
 *
 * ALL    — 전체 (DB 미매칭 항목 포함)
 * AUTO   — AI 자동매매 주문만
 * MANUAL — 사용자 수동 주문만
 *
 * AUTO/MANUAL 필터 시 DB 미매칭(source=null) 항목은 응답에서 제외된다.
 */
public enum HistorySourceFilter {
    ALL, AUTO, MANUAL
}
