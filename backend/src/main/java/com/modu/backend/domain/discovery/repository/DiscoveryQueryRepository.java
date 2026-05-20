package com.modu.backend.domain.discovery.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 종목 추천 (Discovery) 조회 Repository — S14P31B106-362
 *
 * 4 테이블 LEFT JOIN 후 tier 별 ROW_NUMBER 로 상위 N개 추출:
 *   stock_master (is_active)
 *   daily_fundamentals (최신일, risk_tier IS NOT NULL)
 *   daily_indicators (최신일, atr_ratio / volume_spike)
 *   daily_ohlcv (최신일 close + 직전 거래일 close)
 *
 * 정렬: risk_tier ASC, roe_rank_pct ASC NULLS LAST (수익성 백분위 상위 우선)
 *
 * NULL 정책:
 *   - risk_tier IS NULL 종목 제외 (DA compute_risk_tier 실행 전 종목)
 *   - daily_indicators / roe_rank_pct / ohlcv 미적재는 LEFT JOIN 으로 null 허용
 *
 * 사용:
 *   findTopByTierUpToUserGrade(userMaxTier=4, perTier=5)
 *   → tier 1~4 각각 최대 5개 종목 반환. tier 5 는 응답에 빈 블록으로 표시 (Service 책임).
 */
@Repository
@RequiredArgsConstructor
public class DiscoveryQueryRepository {

    /**
     * 핵심 추천 조회 SQL.
     *
     * latest_date  : risk_tier 가 채워진 panel 중 가장 최근 date — 큐레이션 기준일
     * prev_date    : daily_ohlcv 의 latest_date 직전 거래일 — 전일 대비 등락률 계산용
     * candidates   : 4 테이블 조인 + tier 별 PARTITION ROW_NUMBER
     * 최종         : rn <= perTier 만, tier ASC + rank ASC
     *
     * roe_rank_pct NULLS LAST: rank 미산출 (DA compute_fundamental_ranks 미실행) 종목은 후순위.
     * NULLS LAST 가 ASC 정렬에서 NULL 을 마지막에 둠.
     */
    private static final String FIND_TOP_BY_TIER_SQL = """
            WITH latest_date AS (
                SELECT MAX(date) AS d
                FROM daily_fundamentals
                WHERE risk_tier IS NOT NULL
            ),
            prev_date AS (
                SELECT MAX(date) AS d
                FROM daily_ohlcv
                WHERE date < (SELECT d FROM latest_date)
            ),
            candidates AS (
                SELECT
                    sm.stock_code,
                    sm.stock_name,
                    sm.market_type,
                    sm.sector,
                    df.risk_tier,
                    df.per,
                    df.roe,
                    df.roe_rank_pct,
                    df.valuation_status,
                    df.profitability_status,
                    df.growth_status,
                    df.stability_status,
                    di.atr_ratio,
                    di.volume_spike,
                    ohlcv_today.close       AS price,
                    ohlcv_today.date        AS price_date,
                    ohlcv_prev.close        AS prev_close,
                    ROW_NUMBER() OVER (
                        PARTITION BY df.risk_tier
                        ORDER BY df.roe_rank_pct ASC NULLS LAST, df.stock_code ASC
                    ) AS rn
                FROM daily_fundamentals df
                INNER JOIN stock_master sm
                    ON df.stock_code = sm.stock_code
                   AND sm.is_active = TRUE
                LEFT JOIN daily_indicators di
                    ON df.stock_code = di.stock_code AND df.date = di.date
                LEFT JOIN daily_ohlcv ohlcv_today
                    ON df.stock_code = ohlcv_today.stock_code
                   AND ohlcv_today.date = (SELECT d FROM latest_date)
                LEFT JOIN daily_ohlcv ohlcv_prev
                    ON df.stock_code = ohlcv_prev.stock_code
                   AND ohlcv_prev.date = (SELECT d FROM prev_date)
                WHERE df.date = (SELECT d FROM latest_date)
                  AND df.risk_tier IS NOT NULL
                  AND df.risk_tier <= ?
            )
            SELECT
                stock_code, stock_name, market_type, sector,
                risk_tier, per, roe, roe_rank_pct,
                valuation_status, profitability_status, growth_status, stability_status,
                atr_ratio, volume_spike,
                price, price_date, prev_close
            FROM candidates
            WHERE rn <= ?
            ORDER BY risk_tier ASC, rn ASC
            """;

    private final JdbcTemplate jdbcTemplate;

    public List<DiscoveryRow> findTopByTierUpToUserGrade(int userMaxTier, int perTier) {
        return jdbcTemplate.query(
                FIND_TOP_BY_TIER_SQL,
                (rs, rowNum) -> new DiscoveryRow(
                        rs.getString("stock_code"),
                        rs.getString("stock_name"),
                        rs.getString("market_type"),
                        rs.getString("sector"),
                        rs.getInt("risk_tier"),
                        (Double) rs.getObject("per"),
                        (Double) rs.getObject("roe"),
                        (Double) rs.getObject("roe_rank_pct"),
                        rs.getString("valuation_status"),
                        rs.getString("profitability_status"),
                        rs.getString("growth_status"),
                        rs.getString("stability_status"),
                        (Double) rs.getObject("atr_ratio"),
                        (Boolean) rs.getObject("volume_spike"),
                        (Integer) rs.getObject("price"),
                        rs.getObject("price_date", java.time.LocalDate.class),
                        (Integer) rs.getObject("prev_close")
                ),
                userMaxTier,
                perTier
        );
    }
}
