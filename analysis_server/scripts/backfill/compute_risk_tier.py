"""compute_risk_tier

daily_fundamentals.risk_tier 컬럼을 5단계 위험도(1=STABLE ~ 5=AGGRESSIVE)로 채운다.

배경:
    트리거 라우팅을 *보유자 기반* 에서 *종목 위험도 ↔ 유저 risk_grade* 매칭 기반으로
    확장하기 위해 종목별 위험도를 일별 panel 에 사전 계산해둔다.
    분포가 자주 흔들리지 않으므로 (분기/일별) idempotent UPDATE 로 충분.

매핑 표 v1:
    Tier 1 안정형        : stability=stable AND atr_ratio ≤ 0.02 AND KOSPI
                          + roe ≥ 5 AND per > 0 AND valuation ≠ overvalued (부실 침투 차단)
    Tier 2 안정추구형    : stability ∈ {stable, moderate} AND atr_ratio ≤ 0.03 AND KOSPI
                          + per > 0 AND roe ≥ 0
    Tier 3 위험중립형    : stability ≠ risky AND atr_ratio ≤ 0.045
    Tier 4 적극투자형    : stability ≠ risky AND (atr_ratio ≤ 0.06 OR growth ∈ {high, steady})
    Tier 5 공격투자형    : 나머지 (risky / 고변동성 / 데이터 부족 등)

평가 순서:
    CASE WHEN 으로 1→5 순차 매칭. 첫 매칭 tier 채택. Tier 5 가 fallback.

idempotent:
    `risk_tier IS DISTINCT FROM` 가드로 같은 결과는 UPDATE 안 함.
    임계값 변경 후 재실행하면 변경된 row 만 UPDATE.

언제 돌리나:
    1. 최초 1회 — 기존 daily_fundamentals 전체 backfill
    2. 매핑 임계값 튜닝 후 — 재계산
    3. 운영 단계 (선택) — fundamental_loader 가 새 row 적재한 뒤 분기/일별 cron

사용:
    DB_HOST=localhost python -m scripts.backfill.compute_risk_tier
    DB_HOST=localhost python -m scripts.backfill.compute_risk_tier --dry-run

한계 (v2 에서 보완):
    - atr_ratio 절대 임계 (분위 기반이 더 안정적)
    - 시총 미반영 (KOSPI 안에서도 대/중/소형 미구분)
    - 관리종목/거래정지 미반영 (현재는 모두 Tier 5 로 흡수)
"""

import argparse
import logging
import time

from sqlalchemy import text

from clients.postgres_client import get_engine

logger = logging.getLogger(__name__)


# ─── 분류 + UPDATE SQL ──────────────────────────────────────────────────────
#
# JOIN 구조:
#   daily_fundamentals (df)  PK (stock_code, date)
#     LEFT JOIN daily_indicators (di) ON (stock_code, date)   — atr_ratio
#     LEFT JOIN stock_master (sm)    ON (stock_code)          — market_type
#
# LEFT JOIN 이유: di / sm row 가 없어도 df row 는 분류 대상이어야 함.
# 결측 시 atr_ratio = NULL / market_type = NULL → Tier 1·2 컷 자동 탈락 →
# Tier 3·4·5 로 흘러감 (의도된 동작).
#
# COALESCE(col, '') 패턴: status NULL 을 빈 문자열로 치환해 IN/<> 비교 안전.
_COMPUTE_TIER_SQL = text("""
    WITH classified AS (
        SELECT
            df.stock_code,
            df.date,
            CASE
                -- Tier 1: 안정형
                WHEN df.stability_status = 'stable'
                 AND di.atr_ratio IS NOT NULL AND di.atr_ratio <= 0.02
                 AND sm.market_type = 'KOSPI'
                 AND df.roe IS NOT NULL AND df.roe >= 5
                 AND df.per IS NOT NULL AND df.per > 0
                 AND COALESCE(df.valuation_status, '') <> 'overvalued'
                    THEN 1::SMALLINT

                -- Tier 2: 안정추구형
                WHEN df.stability_status IN ('stable', 'moderate')
                 AND di.atr_ratio IS NOT NULL AND di.atr_ratio <= 0.03
                 AND sm.market_type = 'KOSPI'
                 AND df.per IS NOT NULL AND df.per > 0
                 AND df.roe IS NOT NULL AND df.roe >= 0
                    THEN 2::SMALLINT

                -- Tier 3: 위험중립형
                WHEN COALESCE(df.stability_status, '') <> 'risky'
                 AND di.atr_ratio IS NOT NULL AND di.atr_ratio <= 0.045
                    THEN 3::SMALLINT

                -- Tier 4: 적극투자형
                WHEN COALESCE(df.stability_status, '') <> 'risky'
                 AND (
                       (di.atr_ratio IS NOT NULL AND di.atr_ratio <= 0.06)
                       OR df.growth_status IN ('high_growth', 'steady_growth')
                     )
                    THEN 4::SMALLINT

                -- Tier 5: 공격투자형 (fallback — risky / 고변동성 / 결측)
                ELSE 5::SMALLINT
            END AS tier
        FROM daily_fundamentals df
        LEFT JOIN daily_indicators di
            ON df.stock_code = di.stock_code AND df.date = di.date
        LEFT JOIN stock_master sm
            ON df.stock_code = sm.stock_code
    )
    UPDATE daily_fundamentals AS dfu
    SET risk_tier = classified.tier
    FROM classified
    WHERE dfu.stock_code = classified.stock_code
      AND dfu.date = classified.date
      AND dfu.risk_tier IS DISTINCT FROM classified.tier
""")


# 분포 진단 — 매핑 표 검증용. 각 tier 비중이 극단적이면 임계값 재조정 신호.
_DIST_SQL = text("""
    SELECT
        COUNT(*)                                  AS n_total,
        COUNT(*) FILTER (WHERE risk_tier = 1)     AS n_tier1,
        COUNT(*) FILTER (WHERE risk_tier = 2)     AS n_tier2,
        COUNT(*) FILTER (WHERE risk_tier = 3)     AS n_tier3,
        COUNT(*) FILTER (WHERE risk_tier = 4)     AS n_tier4,
        COUNT(*) FILTER (WHERE risk_tier = 5)     AS n_tier5,
        COUNT(*) FILTER (WHERE risk_tier IS NULL) AS n_null,
        MIN(date) AS min_date, MAX(date) AS max_date
    FROM daily_fundamentals
""")

# 최신 날짜 단면만 보는 부가 진단 — 라이브 라우팅 관점에서 더 의미 있는 분포.
_LATEST_DIST_SQL = text("""
    WITH latest AS (
        SELECT DISTINCT ON (stock_code) stock_code, risk_tier
        FROM daily_fundamentals
        ORDER BY stock_code, date DESC
    )
    SELECT
        COUNT(*)                                  AS n_stocks,
        COUNT(*) FILTER (WHERE risk_tier = 1)     AS n_tier1,
        COUNT(*) FILTER (WHERE risk_tier = 2)     AS n_tier2,
        COUNT(*) FILTER (WHERE risk_tier = 3)     AS n_tier3,
        COUNT(*) FILTER (WHERE risk_tier = 4)     AS n_tier4,
        COUNT(*) FILTER (WHERE risk_tier = 5)     AS n_tier5,
        COUNT(*) FILTER (WHERE risk_tier IS NULL) AS n_null
    FROM latest
""")


def main():
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
        datefmt="%H:%M:%S",
    )
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--dry-run", action="store_true",
        help="UPDATE skip. 분포만 출력 (현재 상태 확인용).",
    )
    args = parser.parse_args()

    engine = get_engine()

    if args.dry_run:
        logger.info("--dry-run: UPDATE skip — 현재 분포만 출력")
    else:
        t0 = time.monotonic()
        with engine.begin() as conn:
            logger.info("UPDATE — daily_fundamentals.risk_tier 5단계 분류 시작")
            result = conn.execute(_COMPUTE_TIER_SQL)
        logger.info("✓ UPDATE 완료: %d 행 변경 (%.1fs)",
                    result.rowcount, time.monotonic() - t0)

    # 분포 확인 (별도 connection — 트랜잭션 종료 후).
    with engine.connect() as conn:
        row = conn.execute(_DIST_SQL).first()
        latest = conn.execute(_LATEST_DIST_SQL).first()

    logger.info(
        "[전체 panel] n=%d  T1=%d / T2=%d / T3=%d / T4=%d / T5=%d / NULL=%d  (%s ~ %s)",
        row.n_total,
        row.n_tier1, row.n_tier2, row.n_tier3, row.n_tier4, row.n_tier5,
        row.n_null, row.min_date, row.max_date,
    )
    logger.info(
        "[최신 단면] n=%d  T1=%d / T2=%d / T3=%d / T4=%d / T5=%d / NULL=%d",
        latest.n_stocks,
        latest.n_tier1, latest.n_tier2, latest.n_tier3, latest.n_tier4, latest.n_tier5,
        latest.n_null,
    )


if __name__ == "__main__":
    main()
