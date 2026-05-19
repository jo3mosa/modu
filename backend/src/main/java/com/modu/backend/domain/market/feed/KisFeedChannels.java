package com.modu.backend.domain.market.feed;

/**
 * KIS Market Data Gateway 와 backend 사이의 Redis Pub/Sub 채널 키 규약.
 *
 * [채널 카테고리]
 * - control : backend → gateway 방향. 사용자별 SUBSCRIBE/UNSUBSCRIBE 요청.
 * - tick    : gateway → backend 방향. 사용자별 시세/호가 파싱 결과.
 * - heartbeat : gateway → backend (선택). 사용자 세션 연결 상태 통지.
 *
 * [규약]
 * - 사용자 단위로 fanout — backend pod 는 자신에게 접속한 사용자의 채널만 구독.
 * - 채널명에 podId 등 인스턴스 식별자를 넣지 않음 → backend N개로 자유롭게 확장 가능.
 */
public final class KisFeedChannels {

    /** backend → gateway 단일 채널 */
    public static final String CONTROL = "kis:feed:control";

    /** gateway → backend 의 사용자별 tick 채널 prefix */
    public static final String TICK_USER_PREFIX = "kis:feed:tick:user:";

    /** gateway → backend 의 heartbeat 채널 (선택) */
    public static final String HEARTBEAT = "kis:feed:heartbeat";

    private KisFeedChannels() {
    }

    /** userId 기반 tick 채널 키 생성 */
    public static String tickUserChannel(long userId) {
        return TICK_USER_PREFIX + userId;
    }
}
