package com.modu.backend.domain.strategy.entity;

/**
 * 자동매매 상태 (auto_trade_settings.auto_trade_status) — S14P31B106-292
 *
 * ACTIVE         : 사용자가 자동매매 ON. AI 메시지 정상 처리
 * INACTIVE       : 사용자가 자동매매 OFF (수동 OFF). AI 메시지 수신 시 BLOCKED 기록
 * KILL_SWITCHED  : 시스템이 강제 발동 (KIS 거부 5회 연속 등). 사용자 재요청으로만 해제 가능
 *
 * DB CHECK 제약 (CHK_AUTO_TRADE_STATUS) 의 허용 값 집합과 동기화.
 */
public enum AutoTradeStatus {
    ACTIVE,
    INACTIVE,
    KILL_SWITCHED
}
