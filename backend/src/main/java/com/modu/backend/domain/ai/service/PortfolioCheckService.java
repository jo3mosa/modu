package com.modu.backend.domain.ai.service;

import com.modu.backend.domain.account.dto.AccountSummaryResponse;
import com.modu.backend.domain.account.dto.HoldingResponse;
import com.modu.backend.domain.account.dto.PortfolioResponse;
import com.modu.backend.domain.account.service.AccountService;
import com.modu.backend.domain.trading.execution.service.PortfolioBalanceCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * AI 매매 사전 잔고/보유 검증 (S14P31B106-292)
 *
 * [검증 소스]
 *  1차: portfolio:cache:balance:{userId} (PortfolioBalanceCacheService 의 10s 캐시 + single-flight)
 *  2차 (cache miss): KIS 직접 호출 (AccountService / PortfolioBalanceCacheService 내부 fallback)
 *
 * [268 결정 사항 반영]
 *  - AI 가 보는 portfolio:snapshot (24h TTL) 와 분리. 본 서비스는 짧은 TTL 캐시만 사용 → stale 매매 검증 위험 회피.
 *  - portfolio:snapshot 직접 GET 제거. PortfolioBalanceCacheService.fetch() 를 통한 캐시-우선 흐름.
 *
 * [검증 기준]
 *  BUY  : cash_balance >= order_amount
 *  SELL : holdings 에서 stock_code 의 quantity >= 주문 수량
 *
 * [장애 분리]
 *  Redis 조회 / KIS 조회 실패 시 검증 통과 (true 반환) — 사전 검증은 best-effort,
 *  최종 잔고 확인은 KIS placeOrder 단계 (KisOrderConsumer) 가 담당.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioCheckService {

    private final AccountService accountService;
    private final PortfolioBalanceCacheService portfolioBalanceCacheService;

    /**
     * BUY 검증 — cash_balance >= orderAmount
     * KIS 호출 실패 시 true (검증 skip — KIS placeOrder 가 최종 차단)
     */
    public boolean hasSufficientCash(Long userId, long orderAmount) {
        Long cashBalance = fetchCashBalanceFromKis(userId);
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
        Long heldQuantity = fetchHoldingFromCache(userId, stockCode);
        if (heldQuantity == null) {
            log.warn("SELL 보유 검증 자료 없음 - userId: {}, stockCode: {} (KIS placeOrder 단계 검증에 위임)",
                    userId, stockCode);
            return true;
        }
        return heldQuantity >= quantity;
    }

    // ───────────────────────────────────────────────────────────────────
    // KIS / Cache
    // ───────────────────────────────────────────────────────────────────

    private Long fetchCashBalanceFromKis(Long userId) {
        try {
            AccountSummaryResponse summary = accountService.getAssetSummary(userId);
            return summary.availableCash();
        } catch (Exception e) {
            log.warn("KIS 자산 조회 실패 (BUY 검증) - userId: {}", userId, e);
            return null;
        }
    }

    /**
     * SELL 보유 수량 조회 — portfolio:cache:balance (10s 캐시) 우선, miss 시 KIS 호출 (cache 내부 fallback)
     */
    private Long fetchHoldingFromCache(Long userId, String stockCode) {
        try {
            Optional<PortfolioResponse> portfolio = portfolioBalanceCacheService.fetch(userId);
            if (portfolio.isEmpty()) return null;
            for (HoldingResponse h : portfolio.get().holdings()) {
                if (stockCode.equals(h.stockCode())) {
                    return h.quantity();
                }
            }
            return 0L;
        } catch (Exception e) {
            log.warn("portfolio:cache:balance 조회 실패 (SELL 검증) - userId: {}, stockCode: {}",
                    userId, stockCode, e);
            return null;
        }
    }
}
