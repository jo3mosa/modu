package com.modu.backend.domain.trading.sse.redis;

/**
 * Order SSE 전용 Redis Pub/Sub 채널 규약.
 *
 * [목적]
 * replicas≥2 환경에서 KIS 응답을 처리한 pod 와 사용자의 SSE 연결을 보유한 pod 가 다를 때
 * 이벤트가 drop 되는 라우팅 미스를 fan-out 으로 해소한다.
 *
 * [규약]
 * - 사용자 단위로 채널 분리 — 채널명에 podId 등 인스턴스 식별자를 넣지 않음.
 * - 모든 backend pod 가 패턴 구독 {@link #USER_PATTERN} 으로 자기 사용자만 골라낸다.
 */
public final class OrderSseChannels {

    /** 사용자별 SSE 이벤트 채널 prefix */
    public static final String USER_PREFIX = "order:sse:user:";

    /** 모든 사용자 채널을 잡는 패턴 구독 키 */
    public static final String USER_PATTERN = USER_PREFIX + "*";

    private OrderSseChannels() {
    }

    /** userId 기반 채널 키 생성 */
    public static String userChannel(long userId) {
        return USER_PREFIX + userId;
    }

    /**
     * 채널명에서 userId 추출. 비정상 채널이면 null.
     * subscriber 가 onMessage 의 channel 바이트를 파싱할 때 사용.
     */
    public static Long parseUserId(String channel) {
        if (channel == null || !channel.startsWith(USER_PREFIX)) {
            return null;
        }
        String suffix = channel.substring(USER_PREFIX.length());
        try {
            return Long.parseLong(suffix);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
