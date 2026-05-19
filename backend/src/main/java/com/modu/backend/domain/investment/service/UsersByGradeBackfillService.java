package com.modu.backend.domain.investment.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.modu.backend.domain.investment.entity.InvestmentProfile;
import com.modu.backend.domain.investment.repository.InvestmentProfileRepository;
import com.modu.backend.domain.investment.repository.UsersByGradeRedisRepository;
import com.modu.backend.domain.strategy.dto.InvestmentRiskLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * investment_profiles → Redis users:by_grade:{1~5} 동기화 — S14P31B106-357
 *
 * 호출자:
 *  - UsersByGradeBackfillStartupRunner — 부팅 SCAN 0건 시
 *  - UsersByGradeSyncScheduler — 매일 05:30 KST 정기 재동기 (안전망)
 *
 * 전체 profile 을 메모리에 읽어 grade 별 Map 빌드 → 한 번의 pipeline SADD 로 적재.
 * riskGrade 값이 InvestmentRiskLevel enum 과 매칭 안 되면 WARN 후 skip.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsersByGradeBackfillService {

    private final InvestmentProfileRepository investmentProfileRepository;
    private final UsersByGradeRedisRepository usersByGradeRedisRepository;

    @Transactional(readOnly = true)
    public BackfillResult backfillAll() {
        long startMs = System.currentTimeMillis();
        Map<Integer, List<Long>> gradeToUsers;
        try {
            gradeToUsers = loadAndGroup();
        } catch (Exception e) {
            log.error("[UsersByGradeBackfill] investment_profiles 조회 실패", e);
            return BackfillResult.failed(e.getMessage());
        }

        int totalUsers = gradeToUsers.values().stream().mapToInt(List::size).sum();
        if (totalUsers == 0) {
            log.warn("[UsersByGradeBackfill] investment_profiles 비어있음 — Redis 적재 skip");
            return BackfillResult.empty();
        }

        try {
            usersByGradeRedisRepository.addUsersBatch(gradeToUsers);
        } catch (Exception e) {
            log.error("[UsersByGradeBackfill] Redis 적재 실패 - 사용자 {}건", totalUsers, e);
            return BackfillResult.failed(e.getMessage());
        }
        long elapsed = System.currentTimeMillis() - startMs;
        log.info("[UsersByGradeBackfill] 완료 - 사용자 {}건 (등급별 {}), 소요 {}ms",
                totalUsers, summary(gradeToUsers), elapsed);
        return BackfillResult.success(totalUsers, elapsed);
    }

    private Map<Integer, List<Long>> loadAndGroup() {
        Map<Integer, List<Long>> result = new HashMap<>();
        for (InvestmentProfile profile : investmentProfileRepository.findAll()) {
            Integer gradeInt = toGradeInt(profile.getRiskGrade());
            if (gradeInt == null) {
                log.warn("[UsersByGradeBackfill] 알 수 없는 riskGrade - userId: {}, value: {}",
                        profile.getUserId(), profile.getRiskGrade());
                continue;
            }
            result.computeIfAbsent(gradeInt, k -> new ArrayList<>()).add(profile.getUserId());
        }
        return result;
    }

    private Integer toGradeInt(String riskGrade) {
        if (riskGrade == null) return null;
        try {
            return InvestmentRiskLevel.valueOf(riskGrade).toGradeInt();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String summary(Map<Integer, List<Long>> gradeToUsers) {
        StringBuilder sb = new StringBuilder("{");
        for (int g = 1; g <= 5; g++) {
            sb.append(g).append('=').append(gradeToUsers.getOrDefault(g, List.of()).size());
            if (g < 5) sb.append(", ");
        }
        return sb.append('}').toString();
    }

    public record BackfillResult(int count, long elapsedMs, boolean success, String errorMessage) {
        public static BackfillResult success(int count, long elapsedMs) {
            return new BackfillResult(count, elapsedMs, true, null);
        }
        public static BackfillResult empty() {
            return new BackfillResult(0, 0L, true, null);
        }
        public static BackfillResult failed(String message) {
            return new BackfillResult(0, 0L, false, message);
        }
    }
}
