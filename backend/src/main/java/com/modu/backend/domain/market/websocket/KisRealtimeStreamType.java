package com.modu.backend.domain.market.websocket;

/**
 * KIS 실시간 시세 스트림 타입
 *
 * MODU 내부 타입과 KIS WebSocket TR ID 매핑
 */
public enum KisRealtimeStreamType {

    PRICE("H0STCNT0"),
    ORDERBOOK("H0STASP0");

    private final String trId;

    KisRealtimeStreamType(String trId) {
        this.trId = trId;
    }

    public String trId() {
        return trId;
    }

    /**
     * KIS TR ID 기반 스트림 타입 변환
     */
    public static KisRealtimeStreamType fromTrId(String trId) {
        for (KisRealtimeStreamType type : values()) {
            if (type.trId.equals(trId)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported trId: " + trId);
    }
}
