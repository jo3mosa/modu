package com.modu.backend.domain.market.websocket;

/**
 * 실시간 시세 구독 식별자
 *
 * type + stockCode 기준 세션 그룹핑 및 KIS 구독/해제 처리
 */
public record KisRealtimeStreamKey(
        KisRealtimeStreamType type,
        String stockCode
) {
}
