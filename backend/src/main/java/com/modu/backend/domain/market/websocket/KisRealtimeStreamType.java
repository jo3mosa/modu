package com.modu.backend.domain.market.websocket;

/**
 * KIS 실시간 시세 스트림 타입
 *
 * MODU 내부 타입과 KIS WebSocket TR ID 매핑
 */
public enum KisRealtimeStreamType {

    PRICE("H0STCNT0"),
    ORDERBOOK("H0STASP0"),

    /**
     * 체결통보 (실전) — S14P31B106-291.
     * tr_key = 계좌번호 앞 8자리(CANO). 응답은 AES-256 CBC 암호화 — KisExecutionCipher 로 복호화 필요.
     * 모의계좌는 서비스 미제공이라 H0STCNI9 미지원.
     */
    EXECUTION("H0STCNI0");

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
