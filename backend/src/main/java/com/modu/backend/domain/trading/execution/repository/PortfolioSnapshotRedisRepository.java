package com.modu.backend.domain.trading.execution.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

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
 *  없음 (영구). 268 재시작 복구 대상.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class PortfolioSnapshotRedisRepository {

    private static final String KEY_PREFIX = "portfolio:snapshot:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void set(Long userId, Long cashBalance, Long totalAssets,
                    List<Map<String, Object>> holdings) {
        Map<String, Object> snapshot = Map.of(
                "cash_balance", cashBalance == null ? "" : cashBalance,
                "total_assets", totalAssets == null ? "" : totalAssets,
                "holdings", holdings == null ? List.of() : holdings
        );
        try {
            String json = objectMapper.writeValueAsString(snapshot);
            redisTemplate.opsForValue().set(key(userId), json);
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
