package com.modu.backend.domain.auth.service;

import com.modu.backend.domain.auth.jwt.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class AccessTokenBlacklistService {

    private static final String KEY_PREFIX = "auth:blacklist:access:";

    private final StringRedisTemplate redisTemplate;
    private final JwtProvider jwtProvider;

    public void blacklist(String accessToken, long ttlMillis) {
        if (accessToken == null || accessToken.isBlank() || ttlMillis <= 0) {
            return;
        }
        redisTemplate.opsForValue().set(key(accessToken), "1", Duration.ofMillis(ttlMillis));
    }

    public boolean isBlacklisted(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(key(accessToken)));
    }

    private String key(String accessToken) {
        return KEY_PREFIX + jwtProvider.hashToken(accessToken);
    }
}
