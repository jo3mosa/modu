package com.modu.backend.domain.market.websocket;

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

    public static KisRealtimeStreamType fromTrId(String trId) {
        for (KisRealtimeStreamType type : values()) {
            if (type.trId.equals(trId)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported trId: " + trId);
    }
}

