package com.modu.backend.domain.auth.service;

import com.modu.backend.domain.auth.jwt.JwtProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessTokenBlacklistServiceTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;
    @Mock JwtProvider jwtProvider;

    @Test
    @DisplayName("blacklist stores token hash with ttl")
    void blacklistStoresTokenHashWithTtl() {
        AccessTokenBlacklistService service = new AccessTokenBlacklistService(redisTemplate, jwtProvider);
        when(jwtProvider.hashToken("access-token")).thenReturn("hashed-token");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        service.blacklist("access-token", 1000L);

        verify(valueOperations).set("auth:blacklist:access:hashed-token", "1", Duration.ofMillis(1000L));
    }

    @Test
    @DisplayName("blacklist swallows redis save exception")
    void blacklistSwallowsRedisSaveException() {
        AccessTokenBlacklistService service = new AccessTokenBlacklistService(redisTemplate, jwtProvider);
        when(jwtProvider.hashToken("access-token")).thenReturn("hashed-token");
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));

        assertThatCode(() -> service.blacklist("access-token", 1000L))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("isBlacklisted returns true when token hash exists")
    void isBlacklistedReturnsTrueWhenTokenHashExists() {
        AccessTokenBlacklistService service = new AccessTokenBlacklistService(redisTemplate, jwtProvider);
        when(jwtProvider.hashToken("access-token")).thenReturn("hashed-token");
        when(redisTemplate.hasKey("auth:blacklist:access:hashed-token")).thenReturn(true);

        boolean result = service.isBlacklisted("access-token");

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("isBlacklisted returns false on redis lookup exception")
    void isBlacklistedReturnsFalseOnRedisLookupException() {
        AccessTokenBlacklistService service = new AccessTokenBlacklistService(redisTemplate, jwtProvider);
        when(jwtProvider.hashToken("access-token")).thenReturn("hashed-token");
        when(redisTemplate.hasKey("auth:blacklist:access:hashed-token"))
                .thenThrow(new RuntimeException("redis down"));

        boolean result = service.isBlacklisted("access-token");

        assertThat(result).isFalse();
    }
}
