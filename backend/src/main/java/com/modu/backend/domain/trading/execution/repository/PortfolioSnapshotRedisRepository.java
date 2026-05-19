package com.modu.backend.domain.trading.execution.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis portfolio:snapshot:{user_id} JSON 접근 — S14P31B106-291
 *
 * AI 측 `get_portfolio_snapshot()` 이 읽는 키. 매 체결 시 SET (덮어쓰기).
 *
 * [JSON 형식 — 2026-05-14 AI 팀 명세]
 *   {
 *     "cash_balance": <long|null>,
 *     "total_assets": <long|null>,
 *     "holdings": [{ "stock_code", "stock_name", "quantity", "average_price" }]
 *   }
 *
 * [현 단계 한계]
 *  cash_balance / total_assets 는 KIS inquire-balance output2 매핑 미구현 → null 로 SET.
 *  AI 측은 dict.get 으로 graceful 처리. 정확값 필요 시 followups (output2 매핑).
 *
 * [TTL]
 *  24시간 — 268 (Redis 재시작 복구) AI 팀 합의값. 영구 대신 24h 채택:
 *    - 268 단계 4 (KIS 잔고 1h 주기 검증) 가 portfolio:snapshot 도 재SET → 정상 운영 중에는 항상 refresh
 *    - 사용자 매매 / KIS 호출 없이 N일 경과 시 자연 만료로 stale 영구 잔존 회피
 *  BE 사전 잔고 검증은 별도 키 (portfolio:cache:balance, 10s) 사용 — PortfolioCheckService 참고.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class PortfolioSnapshotRedisRepository {

    private static final String KEY_PREFIX = "portfolio:snapshot:";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void set(Long userId, Long cashBalance, Long totalAssets,
                    List<Map<String, Object>> holdings) {
        // null 그대로 보존 — AI 측 명세는 "숫자 또는 null". 빈 문자열로 치환하면 타입 계약 깨짐.
        // Map.of 는 null 미허용이라 HashMap 사용.
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("cash_balance", cashBalance);
        snapshot.put("total_assets", totalAssets);
        snapshot.put("holdings", holdings == null ? List.of() : holdings);
        try {
            String json = objectMapper.writeValueAsString(snapshot);
            redisTemplate.opsForValue().set(key(userId), json, TTL);
        } catch (JsonProcessingException e) {
            log.error("[PortfolioSnapshot] JSON 직렬화 실패 - userId: {}", userId, e);
        } catch (Exception e) {
            log.error("[PortfolioSnapshot] SET 실패 - userId: {}", userId, e);
        }
    }

    private static String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}
