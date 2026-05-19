package com.modu.backend.global.config;

/**
 * KIS WebSocket Gateway 분리용 Spring profile 상수.
 *
 * - 동일 backend 이미지가 ACTIVE 한 profile 에 따라 두 역할로 동작:
 *   - "gateway" 활성: KIS WebSocket 단일 보유 (UserKisSession + Redis publisher)
 *   - "gateway" 비활성: 프론트 WS 수용 + Redis subscriber 로 시세 fanout 수신
 *
 * - K8s 에서 SPRING_PROFILES_ACTIVE 환경변수로 분기.
 */
public final class KisProfiles {

    public static final String GATEWAY = "gateway";
    public static final String NOT_GATEWAY = "!gateway";

    private KisProfiles() {
    }
}
