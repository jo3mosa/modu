package com.modu.backend.domain.investment.repository;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Redis users:by_grade:{1~5} Set 접근 — S14P31B106-355
 *
 * AI 측 비보유자 매칭에 사용. StrategyProfileService.updateProfile() 완료 후 SADD/SREM,
 * 부팅 backfill (357) 로 investment_profiles 전체 일괄 적재.
 *
 * [TTL]
 *  없음 (영구). profile 변경 hook (357) 으로 증분 유지.
 *
 * [실패 처리]
 *  ERROR 로그만. caller 가 throw 안 하도록 — DB commit 후 Redis 실패는 357 backfill 대상.
 *
 * [Grade 범위]
 *  1~5 외 값은 ERROR 로그 + early return.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class UsersByGradeRedisRepository {

    private static final String KEY_PREFIX = "users:by_grade:";
    private static final int MIN_GRADE = 1;
    private static final int MAX_GRADE = 5;

    private final StringRedisTemplate redisTemplate;

    public void addUser(Long userId, int gradeInt) {
        if (!isValidGrade(gradeInt)) {
            log.error("[UsersByGrade] grade 범위 벗어남 - userId: {}, gradeInt: {}", userId, gradeInt);
            return;
        }
        try {
            redisTemplate.opsForSet().add(key(gradeInt), userId.toString());
        } catch (Exception e) {
            log.error("[UsersByGrade] SADD 실패 - userId: {}, gradeInt: {}", userId, gradeInt, e);
        }
    }

    public void removeUser(Long userId, int gradeInt) {
        if (!isValidGrade(gradeInt)) {
            log.error("[UsersByGrade] grade 범위 벗어남 - userId: {}, gradeInt: {}", userId, gradeInt);
            return;
        }
        try {
            redisTemplate.opsForSet().remove(key(gradeInt), userId.toString());
        } catch (Exception e) {
            log.error("[UsersByGrade] SREM 실패 - userId: {}, gradeInt: {}", userId, gradeInt, e);
        }
    }

    /**
     * 357 backfill — 등급별 사용자 집합 일괄 SADD. Redis pipeline 으로 RTT 절감.
     * 부분 실패 시에도 다른 등급 진행. 등급 단위 실패는 ERROR 로그.
     */
    public void addUsersBatch(Map<Integer, ? extends Collection<Long>> gradeToUsers) {
        if (gradeToUsers == null || gradeToUsers.isEmpty()) return;
        var serializer = redisTemplate.getStringSerializer();
        try {
            redisTemplate.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                gradeToUsers.forEach((gradeInt, userIds) -> {
                    try {
                        if (gradeInt == null || !isValidGrade(gradeInt)) {
                            log.error("[UsersByGrade] batch grade 범위 벗어남 - gradeInt: {}", gradeInt);
                            return;
                        }
                        if (userIds == null || userIds.isEmpty()) return;
                        byte[] keyBytes = serializer.serialize(key(gradeInt));
                        if (keyBytes == null) {
                            log.error("[UsersByGrade] batch key 직렬화 실패 - gradeInt: {}", gradeInt);
                            return;
                        }
                        byte[][] valueBytes = serializeUserIds(userIds, serializer);
                        if (valueBytes.length == 0) return;
                        connection.setCommands().sAdd(keyBytes, valueBytes);
                    } catch (Exception perGrade) {
                        log.error("[UsersByGrade] batch 단일 등급 SADD 실패 - gradeInt: {}", gradeInt, perGrade);
                    }
                });
                return null;
            });
        } catch (Exception e) {
            log.error("[UsersByGrade] batch SADD 실패 - gradeCount: {}", gradeToUsers.size(), e);
        }
    }

    /**
     * 357 검증 / 디버깅용 — 특정 등급의 현재 사용자 집합 조회.
     */
    public Set<String> getUsers(int gradeInt) {
        if (!isValidGrade(gradeInt)) {
            log.error("[UsersByGrade] grade 범위 벗어남 - gradeInt: {}", gradeInt);
            return Set.of();
        }
        try {
            Set<String> members = redisTemplate.opsForSet().members(key(gradeInt));
            return members == null ? Set.of() : members;
        } catch (Exception e) {
            log.error("[UsersByGrade] SMEMBERS 실패 - gradeInt: {}", gradeInt, e);
            return Set.of();
        }
    }

    private static boolean isValidGrade(int gradeInt) {
        return gradeInt >= MIN_GRADE && gradeInt <= MAX_GRADE;
    }

    private static byte[][] serializeUserIds(Collection<Long> userIds,
            org.springframework.data.redis.serializer.RedisSerializer<String> serializer) {
        return userIds.stream()
                .filter(java.util.Objects::nonNull)
                .map(id -> serializer.serialize(id.toString()))
                .filter(java.util.Objects::nonNull)
                .toArray(byte[][]::new);
    }

    private static String key(int gradeInt) {
        return KEY_PREFIX + gradeInt;
    }
}
