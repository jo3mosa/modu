package com.modu.backend.domain.auth.service;

import com.modu.backend.domain.auth.jwt.JwtProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
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
        String key = key(accessToken);
        try {
            redisTemplate.opsForValue().set(key, "1", Duration.ofMillis(ttlMillis));
        } catch (RuntimeException e) {
            log.warn("Access token blacklist save failed - key: {}, error: {}", key, e.getMessage(), e);
        }
    }

    public boolean isBlacklisted(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return false;
        }
        String key = key(accessToken);
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (RuntimeException e) {
            log.warn("Access token blacklist lookup failed - key: {}, error: {}", key, e.getMessage(), e);
            return false;
        }
    }

    private String key(String accessToken) {
        return KEY_PREFIX + jwtProvider.hashToken(accessToken);
    }
}
