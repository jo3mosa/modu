package com.modu.backend.domain.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modu.backend.domain.account.dto.AccountSummaryResponse;
import com.modu.backend.domain.account.dto.HoldingResponse;
import com.modu.backend.domain.account.dto.PortfolioResponse;
import com.modu.backend.domain.account.service.AccountService;
import com.modu.backend.domain.account.service.PortfolioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * AI 매매 사전 잔고/보유 검증 (S14P31B106-292)
 *
 * [검증 소스]
 *  1차: Redis portfolio:snapshot:{userId} (AI 팀 합의 — 체결 핸들러 291 이 갱신)
 *  2차 (Redis miss): KisBalanceClient / KisAssetClient 직접 호출 (AccountService / PortfolioService 추상화 재사용)
 *
 * [검증 기준]
 *  BUY  : cash_balance >= order_amount
 *  SELL : holdings 에서 stock_code 의 quantity >= 주문 수량
 *
 * [장애 분리]
 *  Redis 조회 / KIS 조회 실패 시 검증 통과 (true 반환) — 사전 검증은 best-effort,
 *  최종 잔고 확인은 KIS placeOrder 단계 (KisOrderConsumer) 가 담당.
 *  이 정책은 SignalHandlerService 가 BLOCKED 처리 정책 결정과 결합.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioCheckService {

    private static final String SNAPSHOT_KEY_PREFIX = "portfolio:snapshot:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AccountService accountService;
    private final PortfolioService portfolioService;

    /**
     * BUY 검증 — cash_balance >= orderAmount
     * 조회 실패 / snapshot 없음 + KIS 호출 실패 시 true (검증 skip — KIS placeOrder 가 최종 차단)
     */
    public boolean hasSufficientCash(Long userId, long orderAmount) {
        Long cashBalance = readSnapshotCashBalance(userId);
        if (cashBalance == null) {
            cashBalance = fetchCashBalanceFromKis(userId);
        }
        if (cashBalance == null) {
            log.warn("BUY 잔고 검증 자료 없음 - userId: {} (KIS placeOrder 단계 검증에 위임)", userId);
            return true;
        }
        return cashBalance >= orderAmount;
    }

    /**
     * SELL 검증 — 해당 종목 보유 quantity >= 주문 수량
     */
    public boolean hasSufficientHolding(Long userId, String stockCode, long quantity) {
        Long heldQuantity = readSnapshotHolding(userId, stockCode);
        if (heldQuantity == null) {
            heldQuantity = fetchHoldingFromKis(userId, stockCode);
        }
        if (heldQuantity == null) {
            log.warn("SELL 보유 검증 자료 없음 - userId: {}, stockCode: {} (KIS placeOrder 단계 검증에 위임)",
                    userId, stockCode);
            return true;
        }
        return heldQuantity >= quantity;
    }

    // ───────────────────────────────────────────────────────────────────
    // Redis snapshot
    // ───────────────────────────────────────────────────────────────────

    private Long readSnapshotCashBalance(Long userId) {
        JsonNode snapshot = readSnapshot(userId);
        if (snapshot == null) return null;
        JsonNode cash = snapshot.get("cash_balance");
        return cash != null && cash.isNumber() ? cash.asLong() : null;
    }

    private Long readSnapshotHolding(Long userId, String stockCode) {
        JsonNode snapshot = readSnapshot(userId);
        if (snapshot == null) return null;
        JsonNode holdings = snapshot.get("holdings");
        if (holdings == null || !holdings.isArray()) return null;
        for (JsonNode h : holdings) {
            JsonNode code = h.get("stock_code");
            if (code != null && stockCode.equals(code.asText())) {
                JsonNode qty = h.get("quantity");
                return qty != null && qty.isNumber() ? qty.asLong() : null;
            }
        }
        return 0L; // snapshot 있는데 해당 종목 보유 없음 → 0
    }

    private JsonNode readSnapshot(Long userId) {
        try {
            String raw = redisTemplate.opsForValue().get(SNAPSHOT_KEY_PREFIX + userId);
            if (raw == null || raw.isBlank()) return null;
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            log.warn("portfolio:snapshot 조회/파싱 실패 - userId: {}", userId, e);
            return null;
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // KIS fallback (snapshot miss 시)
    // ───────────────────────────────────────────────────────────────────

    private Long fetchCashBalanceFromKis(Long userId) {
        try {
            AccountSummaryResponse summary = accountService.getAssetSummary(userId);
            return summary.availableCash();
        } catch (Exception e) {
            log.warn("KIS 자산 조회 실패 (BUY fallback) - userId: {}", userId, e);
            return null;
        }
    }

    private Long fetchHoldingFromKis(Long userId, String stockCode) {
        try {
            PortfolioResponse portfolio = portfolioService.getPortfolio(userId);
            for (HoldingResponse h : portfolio.holdings()) {
                if (stockCode.equals(h.stockCode())) {
                    return h.quantity();
                }
            }
            return 0L;
        } catch (Exception e) {
            log.warn("KIS 잔고 조회 실패 (SELL fallback) - userId: {}, stockCode: {}", userId, stockCode, e);
            return null;
        }
    }
}
