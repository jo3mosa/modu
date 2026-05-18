package com.modu.backend.domain.trading.execution.listener;

import com.modu.backend.domain.account.dto.HoldingResponse;
import com.modu.backend.domain.account.dto.PortfolioResponse;
import com.modu.backend.domain.trading.entity.OrderSide;
import com.modu.backend.domain.trading.execution.event.OrderExecutedEvent;
import com.modu.backend.domain.trading.execution.repository.PositionIndexRedisRepository;
import com.modu.backend.domain.trading.execution.repository.PortfolioSnapshotRedisRepository;
import com.modu.backend.domain.trading.execution.service.PortfolioBalanceCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 체결 반영 후 Redis 3종 키 동기화 — S14P31B106-291
 *
 * AFTER_COMMIT 시점에 발화 — DB 갱신이 확정된 후만 Redis 가 변경됨 (DB rollback 시 Redis 무영향).
 *
 * [Redis 갱신]
 *  - position:index:stock:{code}
 *      BUY 첫 체결: SADD (이미 있어도 idempotent)
 *      SELL 전량 체결 (isFinalFill=true): SREM
 *      BUY 부분 체결 / SELL 부분 체결: 변경 없음
 *  - portfolio:snapshot:{userId}
 *      매 체결 시 KIS 잔고 재조회 (10s 캐시) → SET
 *
 * [실패 격리]
 *  Redis 호출 실패는 ERROR 로그만. 268 (재시작 복구) 가 결국 backfill.
 *  KIS 잔고 호출 실패 시 snapshot SET skip (caller 가 다음 체결 시 재시도).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderExecutedRedisSyncListener {

    private final PositionIndexRedisRepository positionIndexRedisRepository;
    private final PortfolioSnapshotRedisRepository portfolioSnapshotRedisRepository;
    private final PortfolioBalanceCacheService portfolioBalanceCacheService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderExecuted(OrderExecutedEvent event) {
        // KIS 잔고 1회 조회 — position-index 결정 + snapshot SET 공통 데이터 소스
        Optional<PortfolioResponse> portfolio = portfolioBalanceCacheService.fetch(event.userId());
        updatePositionIndex(event, portfolio);
        updatePortfolioSnapshot(event, portfolio);
    }

    private void updatePositionIndex(OrderExecutedEvent event, Optional<PortfolioResponse> portfolio) {
        if (event.side() == OrderSide.BUY) {
            // BUY 체결 시 SADD (이미 있어도 idempotent)
            positionIndexRedisRepository.addUser(event.stockCode(), event.userId());
            return;
        }
        if (event.side() == OrderSide.SELL && event.isFinalFill()) {
            // 다중 매수 lot 보유자 보호 — 단일 SELL 주문 전량 체결 ≠ 종목 전량 매도.
            // KIS 잔고에 같은 종목 보유 수량이 남아있으면 SREM 금지.
            if (stillHolding(event.stockCode(), portfolio)) {
                log.info("[RedisSync] SELL 전량 체결이지만 종목 보유 잔여 — SREM skip. userId: {}, stock: {}",
                        event.userId(), event.stockCode());
                return;
            }
            positionIndexRedisRepository.removeUser(event.stockCode(), event.userId());
        }
        // SELL 부분 체결은 변경 없음 (보유 사용자 유지)
    }

    /**
     * KIS 잔고 응답에 동일 종목 보유 수량 > 0 이면 still holding.
     * 잔고 조회 실패 (Optional.empty) 시 보수적으로 true 반환 → SREM 보류 (다음 체결 시 재시도).
     */
    private static boolean stillHolding(String stockCode, Optional<PortfolioResponse> portfolio) {
        if (portfolio.isEmpty()) return true;
        return portfolio.get().holdings().stream()
                .anyMatch(h -> stockCode.equals(h.stockCode())
                        && h.quantity() != null && h.quantity() > 0L);
    }

    private void updatePortfolioSnapshot(OrderExecutedEvent event, Optional<PortfolioResponse> portfolio) {
        if (portfolio.isEmpty()) {
            log.warn("[RedisSync] portfolio snapshot 조회 실패 — SET skip. userId: {}", event.userId());
            return;
        }
        List<Map<String, Object>> holdings = toHoldingsMap(portfolio.get());
        // cash_balance / total_assets 는 KIS output2 매핑 미구현 — null 로 전송 (followups)
        portfolioSnapshotRedisRepository.set(event.userId(), null, null, holdings);
    }

    private static List<Map<String, Object>> toHoldingsMap(PortfolioResponse portfolio) {
        if (portfolio.holdings() == null) return List.of();
        return portfolio.holdings().stream()
                .map(OrderExecutedRedisSyncListener::toMap)
                .toList();
    }

    private static Map<String, Object> toMap(HoldingResponse h) {
        // LinkedHashMap 으로 명세 순서 유지
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("stock_code", h.stockCode());
        map.put("stock_name", h.stockName());
        map.put("quantity", h.quantity());
        map.put("average_price", h.avgBuyPrice());
        return map;
    }
}
