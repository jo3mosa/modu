package com.modu.backend.domain.trading.sse.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OrderSseChannels 단위 테스트 — 채널명 생성/파싱 양방향 보장.
 *
 * subscriber 가 onMessage 의 channel 바이트에서 userId 를 정확히 복원해야 cross-pod 전달이 성립한다.
 */
class OrderSseChannelsTest {

    @Test
    @DisplayName("userChannel — prefix + userId")
    void userChannel_생성() {
        assertThat(OrderSseChannels.userChannel(123L))
                .isEqualTo("order:sse:user:123");
    }

    @Test
    @DisplayName("parseUserId — 정상 채널에서 Long 추출")
    void parseUserId_정상() {
        assertThat(OrderSseChannels.parseUserId("order:sse:user:123"))
                .isEqualTo(123L);
    }

    @Test
    @DisplayName("parseUserId — prefix 안 맞으면 null")
    void parseUserId_prefix_불일치_null() {
        assertThat(OrderSseChannels.parseUserId("market:tick:user:123")).isNull();
        assertThat(OrderSseChannels.parseUserId("foo")).isNull();
    }

    @Test
    @DisplayName("parseUserId — 숫자 아닌 suffix 면 null")
    void parseUserId_숫자아님_null() {
        assertThat(OrderSseChannels.parseUserId("order:sse:user:abc")).isNull();
        assertThat(OrderSseChannels.parseUserId("order:sse:user:")).isNull();
    }

    @Test
    @DisplayName("parseUserId — null 입력 안전")
    void parseUserId_null_입력() {
        assertThat(OrderSseChannels.parseUserId(null)).isNull();
    }

    @Test
    @DisplayName("USER_PATTERN — prefix + *")
    void USER_PATTERN_검증() {
        assertThat(OrderSseChannels.USER_PATTERN).isEqualTo("order:sse:user:*");
    }
}
