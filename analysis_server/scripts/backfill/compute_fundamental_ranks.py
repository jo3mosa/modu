"""compute_fundamental_ranks

daily_fundamentals 에 cross-sectional percentile rank 컬럼을 추가하고 일괄 계산.

배경:
    펀더멘털(ROE/PER/PBR)은 사업보고서 갱신 시점(통상 익년 4월)에만 변경되므로
    매 cycle 마다 cross-section rank 를 계산할 필요가 없다. 사전 계산해두면
    signal_builder 는 단순 SELECT 만 하면 됨.

추가 컬럼:
    roe_rank_pct  DOUBLE PRECISION
        — 같은 날짜의 모든 종목 중 ROE 가 큰 순위 (0 = 최고, 1 = 최저).
        — 상위 N% 풀 = roe_rank_pct ≤ N/100.
        — ROE 가 NULL 인 종목은 rank_pct 도 NULL.

언제 돌리나:
    1. 최초 1회 (현재) — 기존 23-25 daily_fundamentals 전체에 backfill
    2. historical_fundamental_loader 가 새 fiscal year 적용한 뒤 — 분기 갱신 시 재계산

idempotent: 컬럼 존재 시 ALTER 무동작. UPDATE 는 같은 결과로 덮어쓰기.

사용:
    DB_HOST=localhost python -m scripts.backfill.compute_fundamental_ranks
    DB_HOST=localhost python -m scripts.backfill.compute_fundamental_ranks --dry-run
"""

import argparse
import logging
import time

from sqlalchemy import text

from clients.postgres_client import get_engine

logger = logging.getLogger(__name__)


# 컬럼 추가 — PostgreSQL 9.6+ 의 IF NOT EXISTS 로 idempotent.
_ADD_COL_SQL = text("""
    ALTER TABLE daily_fundamentals
    ADD COLUMN IF NOT EXISTS roe_rank_pct DOUBLE PRECISION
""")

# 인덱스 — signal_builder 의 (stock_code, date DESC LIMIT 1) 조회에 이미 활용되므로
# rank_pct 자체 인덱스는 불필요. 필터 (rank_pct <= 0.20) 는 단일 row 조회 후 in-memory.

# Cross-sectional ROE rank 계산 + UPDATE.
# PERCENT_RANK 는 (rank-1)/(N-1) → 0 ~ 1. ORDER BY DESC 라 0 이 최상위.
# 모든 행 update (NULL 행은 rank_pct = NULL 로 reset) — "ROE NULL ↔ rank NULL"
# 계약 유지. 과거 실행에서 채워진 행의 ROE 가 NULL 로 바뀌어도 옛 순위 잔존 X.
_COMPUTE_RANK_SQL = text("""
    WITH ranked AS (
        SELECT stock_code, date,
               CASE WHEN roe IS NULL THEN NULL
                    ELSE PERCENT_RANK() OVER (
                        PARTITION BY date
                        ORDER BY roe DESC NULLS LAST
                    )
               END AS rank_pct
        FROM daily_fundamentals
    )
    UPDATE daily_fundamentals AS dfu
    SET roe_rank_pct = ranked.rank_pct
    FROM ranked
    WHERE dfu.stock_code = ranked.stock_code
      AND dfu.date = ranked.date
      AND dfu.roe_rank_pct IS DISTINCT FROM ranked.rank_pct
""")

# 진단용 — 분포 확인.
_DIST_SQL = text("""
    SELECT
        COUNT(*)                                       AS n_total,
        COUNT(roe_rank_pct)                            AS n_with_rank,
        COUNT(*) FILTER (WHERE roe_rank_pct <= 0.20)   AS n_top20,
        COUNT(*) FILTER (WHERE roe_rank_pct <= 0.50)   AS n_top50,
        MIN(date) AS min_date, MAX(date) AS max_date
    FROM daily_fundamentals
""")


def main():
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
        datefmt="%H:%M:%S",
    )
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true",
                        help="ALTER 까지만 실행. UPDATE 는 skip.")
    args = parser.parse_args()

    engine = get_engine()

    # ALTER 와 UPDATE 는 별도 트랜잭션 — ADD COLUMN ACCESS EXCLUSIVE 락이
    # 수 분 소요되는 UPDATE 까지 유지되면 signal_builder 등 동시 reader 차단.
    t0 = time.monotonic()
    with engine.begin() as conn:
        logger.info("ALTER TABLE — daily_fundamentals.roe_rank_pct 컬럼 (idempotent)")
        conn.execute(_ADD_COL_SQL)
        logger.info("✓ ALTER 완료 (%.1fs)", time.monotonic() - t0)

    if args.dry_run:
        logger.info("--dry-run: UPDATE skip")
        return

    t1 = time.monotonic()
    with engine.begin() as conn:
        logger.info("UPDATE — cross-sectional ROE rank 계산 (수 분 소요 예상)")
        result = conn.execute(_COMPUTE_RANK_SQL)
    logger.info("✓ UPDATE 완료: %d 행 (%.1fs)",
                result.rowcount, time.monotonic() - t1)

    # 분포 확인 (별도 connection — 트랜잭션 종료 후).
    with engine.connect() as conn:
        row = conn.execute(_DIST_SQL).first()
    logger.info(
        "분포: 전체=%d / rank 있음=%d / 상위20%%=%d / 상위50%%=%d / 구간=%s ~ %s",
        row.n_total, row.n_with_rank,
        row.n_top20, row.n_top50,
        row.min_date, row.max_date,
    )


if __name__ == "__main__":
    main()
