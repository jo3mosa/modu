"""fundamental_loader (live)

오늘 날짜 기준 `daily_fundamentals` 행 적재.

backfill 의 `scripts/backfill/historical_fundamental_loader.py` 가 채워둔
`financial_statements` raw 데이터를 활용하고, 라이브 단계에선:
  1. `financial_statements` 에서 fiscal_year + (fiscal_year - 1) 행 SELECT
  2. KIS 실시간 현재가 조회
  3. _compute_one_row 로 ratios + status 계산
  4. `daily_fundamentals` 에 (stock, today) row upsert

architecture: 분기 트리거지만 KIS 현재가 반영 위해 일별 실행도 의미 있음.
Phase 5 에서 docker-compose cron job 또는 k8s CronJob 으로 자동화 예정.

`financial_statements` 자체는 이 모듈이 갱신하지 않음 — backfill 책임.
누락된 fiscal_year 가 많으면 `historical_fundamental_loader.fetch_annual_statements`
별도 실행 후 다시 돌리세요.

사용법:
    python -m collectors.fundamental_loader              # 활성 종목 전체
    python -m collectors.fundamental_loader --stock 005930   # 단일 (테스트)
    python -m collectors.fundamental_loader --date 2026-05-12  # 특정 날짜 (백필)
"""

import argparse
import logging
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from zoneinfo import ZoneInfo

from sqlalchemy import text

from clients.kis_api_client import KisApiClient
from clients.postgres_client import get_engine
from collectors.candle_collector import RateLimiter
# 재계산 로직·SQL 은 backfill 모듈을 single source of truth 로 재사용.
from scripts.backfill.historical_fundamental_loader import (
    DAILY_FUND_INSERT_SQL,
    _compute_one_row,
    pick_fiscal_year,
)

logger = logging.getLogger(__name__)

# 처리 기준일은 한국시간. UTC 컨테이너에서 datetime.now() 그대로 쓰면
# KST 새벽 시간대에 전일 날짜로 적재되어 pick_fiscal_year 도 어긋남.
KST = ZoneInfo("Asia/Seoul")


# ─── 설정 ────────────────────────────────────────────────────────────────────

# candle_collector 와 동일 — KIS rate limit 안전 마진.
KIS_CALLS_PER_SEC = 16
KIS_WORKER_THREADS = 8

# 사업보고서 (annual). 분기/반기 보고서가 필요해지면 확장.
REPRT_CODE_ANNUAL = "11011"


# ─── DB 로딩 ────────────────────────────────────────────────────────────────

_ACTIVE_STOCKS_SQL = text(
    "SELECT stock_code FROM stock_master WHERE is_active = TRUE ORDER BY stock_code"
)

_FS_ROW_SQL = text(
    "SELECT * FROM financial_statements "
    "WHERE stock_code = :stock_code AND fiscal_year = :fiscal_year "
    "  AND reprt_code = :reprt_code"
)


def load_active_stocks() -> list[str]:
    with get_engine().connect() as conn:
        rows = conn.execute(_ACTIVE_STOCKS_SQL).fetchall()
    return [r[0] for r in rows]


def _load_fs_row(conn, stock_code: str, fiscal_year: int):
    """financial_statements 단일 행 (annual report). 없으면 None.

    .mappings().first() 로 RowMapping(dict-like) 반환 — _compute_one_row 가
    row["net_income"] 식의 키 접근을 하므로 sqlite3.Row 와 호환.
    """
    return conn.execute(
        _FS_ROW_SQL,
        {
            "stock_code": stock_code,
            "fiscal_year": fiscal_year,
            "reprt_code": REPRT_CODE_ANNUAL,
        },
    ).mappings().first()


# ─── 1 종목 처리 ────────────────────────────────────────────────────────────

def process_stock(
    stock_code: str,
    date_str: str,
    fiscal_year: int,
    kis: KisApiClient,
) -> bool:
    """1 종목 → daily_fundamentals upsert. 성공 True, skip/fail False.

    Postgres 는 multi-writer (MVCC) — SQLite 시절의 _DB_WRITE_LOCK 직렬화 불요.
    """
    try:
        engine = get_engine()
        with engine.connect() as conn:
            curr_row = _load_fs_row(conn, stock_code, fiscal_year)
            prev_row = _load_fs_row(conn, stock_code, fiscal_year - 1)

        if curr_row is None:
            return False  # financial_statements 미존재 — backfill 필요

        snapshot = kis.get_realtime_snapshot(stock_code)
        close = snapshot["close"] if snapshot else None
        if close is None:
            return False  # KIS 실패 시 PER/PBR 산출 불가 → skip

        row = _compute_one_row(stock_code, date_str, close, curr_row, prev_row)
        if row is None:
            return False

        raw_conn = engine.raw_connection()
        try:
            cursor = raw_conn.cursor()
            cursor.execute(DAILY_FUND_INSERT_SQL, row)
            raw_conn.commit()
        finally:
            raw_conn.close()
        return True
    except Exception as e:
        logger.warning("process_stock(%s) failed: %s", stock_code, e)
        return False


# ─── 사이클 ─────────────────────────────────────────────────────────────────

def run_once(
    kis: KisApiClient,
    date_str: str | None = None,
    calls_per_sec: float = KIS_CALLS_PER_SEC,
    workers: int = KIS_WORKER_THREADS,
    stock_filter: list[str] | None = None,
) -> dict:
    """주어진 날짜의 daily_fundamentals 1 사이클 적재 (병렬)."""
    date_str = date_str or datetime.now(KST).strftime("%Y-%m-%d")
    fiscal_year = pick_fiscal_year(date_str)
    logger.info("processing date=%s, fiscal_year=%d", date_str, fiscal_year)

    stocks = stock_filter or load_active_stocks()
    if not stocks:
        return {"ok": 0, "fail": 0, "total": 0, "elapsed_sec": 0.0}

    kis.ensure_token()
    limiter = RateLimiter(calls_per_sec)
    started = time.monotonic()

    def worker(stock_code: str) -> bool:
        limiter.wait()
        return process_stock(stock_code, date_str, fiscal_year, kis)

    ok = fail = 0
    with ThreadPoolExecutor(max_workers=workers, thread_name_prefix="fund") as ex:
        for success in ex.map(worker, stocks):
            if success:
                ok += 1
            else:
                fail += 1

    elapsed = time.monotonic() - started
    rate = len(stocks) / elapsed if elapsed > 0 else 0
    logger.info(
        "cycle done: %d ok / %d fail / %d total / %.1fs "
        "(%.2f stocks/s, workers=%d, rate=%.1f/s)",
        ok, fail, len(stocks), elapsed, rate, workers, calls_per_sec,
    )
    return {"ok": ok, "fail": fail, "total": len(stocks), "elapsed_sec": elapsed}


# ─── CLI ────────────────────────────────────────────────────────────────────

def main():
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )
    parser = argparse.ArgumentParser(
        description="fundamental_loader — financial_statements + KIS → daily_fundamentals (today)",
    )
    parser.add_argument("--stock", help="단일 종목만 처리 (테스트용)")
    parser.add_argument("--date", help="처리 기준 날짜 (YYYY-MM-DD). 기본은 오늘")
    parser.add_argument("--workers", type=int, default=KIS_WORKER_THREADS,
                        help=f"동시 워커 (기본 {KIS_WORKER_THREADS})")
    parser.add_argument("--rate", type=float, default=KIS_CALLS_PER_SEC,
                        help=f"KIS 초당 호출 한도 (기본 {KIS_CALLS_PER_SEC})")
    args = parser.parse_args()

    kis = KisApiClient()
    stock_filter = [args.stock] if args.stock else None
    run_once(kis, args.date, args.rate, args.workers, stock_filter)


if __name__ == "__main__":
    main()
