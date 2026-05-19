package com.modu.backend.domain.trading.execution.service;

import com.modu.backend.domain.trading.execution.repository.PositionIndexRedisRepository;
import com.modu.backend.domain.trading.position.repository.PositionThresholdRepository;
import com.modu.backend.domain.trading.position.repository.PositionThresholdRepository.UserStockPair;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 268 단계 4 — position:index Redis 키 backfill (DB → Redis)
 *
 * [입력]
 *  position_thresholds 의 is_active=TRUE 행 전체 (user_id, stock_code 쌍)
 *
 * [출력]
 *  position:index:stock:{code} Set 에 사용자 SADD (Redis pipeline)
 *
 * [트리거 시점]
 *  - PortfolioBackfillStartupRunner: 부팅 시 SCAN 결과 0건일 때
 *  - 외부 수동 호출 (운영 reconcile)
 *
 * [책임 경계]
 *  본 서비스는 DB 기준으로 Redis 채움. DB-KIS drift 보정은 단계 4 (KisVerificationService) 가 별도.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PositionIndexBackfillService {

    private final PositionThresholdRepository positionThresholdRepository;
    private final PositionIndexRedisRepository positionIndexRedisRepository;

    public BackfillResult backfillFromDb() {
        long startedAt = System.currentTimeMillis();
        List<UserStockPair> pairs = positionThresholdRepository.findAllActivePairs();
        if (pairs.isEmpty()) {
            log.info("[PositionIndexBackfill] 활성 포지션 없음 — backfill skip");
            return new BackfillResult(0, 0, 0L);
        }

        Map<String, Set<Long>> grouped = new HashMap<>();
        for (UserStockPair pair : pairs) {
            grouped.computeIfAbsent(pair.stockCode(), k -> new HashSet<>()).add(pair.userId());
        }

        positionIndexRedisRepository.addUsersBatch(grouped);

        long elapsed = System.currentTimeMillis() - startedAt;
        int stockCount = grouped.size();
        int pairCount = pairs.size();
        log.info("[PositionIndexBackfill] 완료 — 종목: {}, 사용자-종목 쌍: {}, 소요: {}ms",
                stockCount, pairCount, elapsed);
        return new BackfillResult(stockCount, pairCount, elapsed);
    }

    public record BackfillResult(int stockCount, int pairCount, long elapsedMs) {}
}
