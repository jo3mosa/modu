package com.modu.backend.domain.trading.execution.repository;

/**
 * 외부 거래 drift 방향 — S14P31B106-361 (followups 2.10 A-1 알림 강화)
 *
 * KIS 잔고와 BE 의 position_thresholds 활성 row 가 어긋난 방향을 구분.
 * 방향 별로 별도 dedup 캐시 키를 두어 방향 전환 시 새 발견으로 인식.
 *
 *  - KIS_HOLDING_DB_INACTIVE : KIS 에 보유 / DB position_thresholds 비활성 (외부 매수 추정)
 *  - KIS_MISSING_DB_ACTIVE   : KIS 미보유 / DB 활성 (외부 매도 추정)
 */
public enum PositionDriftDirection {
    KIS_HOLDING_DB_INACTIVE,
    KIS_MISSING_DB_ACTIVE
}
