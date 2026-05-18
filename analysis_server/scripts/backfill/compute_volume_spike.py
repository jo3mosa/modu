"""compute_volume_spike

daily_indicators 에 volume_spike 컬럼 추가 + 일괄 계산.

배경:
    candle_collector 가 라이브에서 계산하는 volume_spike 를 historical 백테스트에도
    동일하게 제공하기 위해 daily_indicators 에 사전 계산.
    detection_engine 의 VOL-001 / TPL-001 / TPL-002 / TPL-003 평가에 사용.

정의 (candle_collector.VOLUME_SPIKE_MULTIPLIER 와 일치):
    volume_spike = (오늘 volume) > 2.0 × (직전 20일 평균 volume)
    — 오늘은 평균 계산에서 제외 (ROWS BETWEEN 20 PRECEDING AND 1 PRECEDING).

언제 돌리나:
    1. 최초 1회 (현재) — 기존 daily_indicators 전체 backfill
    2. historical_indicator_loader 가 새 데이터 적재한 뒤 — 또는 그 안에 통합

idempotent. UPDATE 는 결과로 덮어쓰기.

사용:
    DB_HOST=localhost python -m scripts.backfill.compute_volume_spike
    DB_HOST=localhost python -m scripts.backfill.compute_volume_spike --dry-run
"""

import argparse
import logging
import time

from sqlalchemy import text

from clients.postgres_client import get_engine

logger = logging.getLogger(__name__)

# candle_collector 와 동일 상수.
VOLUME_SPIKE_MULTIPLIER = 2.0


# 컬럼 추가 — idempotent.
_ADD_COL_SQL = text("""
    ALTER TABLE daily_indicators
    ADD COLUMN IF NOT EXISTS volume_spike BOOLEAN
""")

# 일괄 계산 + UPDATE.
# JOIN 으로 daily_ohlcv 의 volume 을 가져와 직전 20일 평균과 비교.
# ROWS BETWEEN 20 PRECEDING AND 1 PRECEDING = 오늘 제외 직전 20일.
_COMPUTE_SQL = text(f"""
    WITH vol_with_avg AS (
        SELECT stock_code, date, volume,
               AVG(volume) OVER (
                   PARTITION BY stock_code
                   ORDER BY date
                   ROWS BETWEEN 20 PRECEDING AND 1 PRECEDING
               ) AS avg_20d
        FROM daily_ohlcv
    ),
    spike_calc AS (
        SELECT stock_code, date,
               CASE
                   WHEN avg_20d IS NULL OR avg_20d = 0 THEN FALSE
                   ELSE volume > {VOLUME_SPIKE_MULTIPLIER} * avg_20d
               END AS spike
        FROM vol_with_avg
    )
    UPDATE daily_indicators di
    SET volume_spike = spike_calc.spike
    FROM spike_calc
    WHERE di.stock_code = spike_calc.stock_code
      AND di.date = spike_calc.date
""")

# 분포 확인용.
_DIST_SQL = text("""
    SELECT
        COUNT(*)                                  AS n_total,
        COUNT(volume_spike)                       AS n_with_value,
        COUNT(*) FILTER (WHERE volume_spike)      AS n_spike_true,
        MIN(date) AS min_date, MAX(date) AS max_date
    FROM daily_indicators
""")


def main():
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
        datefmt="%H:%M:%S",
    )
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true",
                        help="ALTER 까지만. UPDATE skip.")
    args = parser.parse_args()

    engine = get_engine()

    # ALTER 와 UPDATE 는 별도 트랜잭션 — ADD COLUMN ACCESS EXCLUSIVE 락이
    # 수 분 소요되는 UPDATE 까지 유지되면 동시 reader 차단.
    t0 = time.monotonic()
    with engine.begin() as conn:
        logger.info("ALTER TABLE — daily_indicators.volume_spike (idempotent)")
        conn.execute(_ADD_COL_SQL)
        logger.info("✓ ALTER 완료 (%.1fs)", time.monotonic() - t0)

    if args.dry_run:
        logger.info("--dry-run: UPDATE skip")
        return

    t1 = time.monotonic()
    with engine.begin() as conn:
        logger.info("UPDATE — volume_spike 일괄 계산 (수 분 소요 예상)")
        result = conn.execute(_COMPUTE_SQL)
    logger.info("✓ UPDATE 완료: %d 행 (%.1fs)",
                result.rowcount, time.monotonic() - t1)

    # 분포 확인.
    with engine.connect() as conn:
        row = conn.execute(_DIST_SQL).first()
    spike_pct = (row.n_spike_true / row.n_with_value * 100
                 if row.n_with_value else 0)
    logger.info(
        "분포: 전체=%d / 값 있음=%d / spike=True=%d (%.1f%%) / 구간=%s ~ %s",
        row.n_total, row.n_with_value,
        row.n_spike_true, spike_pct,
        row.min_date, row.max_date,
    )


if __name__ == "__main__":
    main()
