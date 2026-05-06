package com.modu.backend.domain.market.websocket;

public record KisRealtimeStreamKey(
        KisRealtimeStreamType type,
        String stockCode
) {
}

