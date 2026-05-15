"""migrate_sqlite_to_postgres.py

일회성 마이그레이션 — `data/*.db` (SQLite) 의 분석 테이블을 Postgres `modu_db` 로 이관.

기본 시나리오 (A안):
  - daily_ohlcv         <-  stock_master_recovered.db  (clean, quick_check ok)
  - daily_fundamentals  <-  stock_master_recovered.db  (clean)
  - financial_statements <- stock_master.db            (corrupted, skip 모드)
  - daily_indicators    SKIP (Postgres 적재 후 historical_indicator_loader 로 재계산)

stock_master.db 손상 페이지를 만나면 row 단위 SELECT 로 fallback —
정상 row 만 가져오고 깨진 row 는 건너뛴다.

전략:
  - clean 파일: chunked SELECT + COPY FROM STDIN (50k rows/청크, 빠름)
  - corrupted 파일: row-by-row SELECT, 깨진 row skip 후 STDIN buffer 적재

사용:
    # 기본 (A안 그대로)
    python -m scripts.backfill.migrate_sqlite_to_postgres

    # 이미 Postgres 측 데이터 있으면 비우고 재이관
    python -m scripts.backfill.migrate_sqlite_to_postgres --truncate

    # source 파일 override
    python -m scripts.backfill.migrate_sqlite_to_postgres --recovered ./other_clean.db
"""

import argparse
import io
import os
import sqlite3
import struct
import time

from clients.postgres_client import get_engine


_DATA_DIR = os.path.join(os.path.dirname(__file__), "..", "..", "data")
_DEFAULT_RECOVERED = os.path.join(_DATA_DIR, "stock_master_recovered.db")
_DEFAULT_MAIN = os.path.join(_DATA_DIR, "stock_master.db")

CHUNK_SIZE = 50_000


# 테이블 컬럼 정의 — (이름, 타입). 순서가 Postgres 컬럼 순서와 일치해야 함.
# 타입: "text" / "int" / "real". `.recover` 산출물의 BLOB 디코딩에 사용.
SCHEMA = {
    "daily_ohlcv": [
        ("stock_code", "text"),
        ("date",       "text"),
        ("open",       "int"),
        ("high",       "int"),
        ("low",        "int"),
        ("close",      "int"),
        ("volume",     "int"),
    ],
    "daily_fundamentals": [
        ("stock_code",           "text"),
        ("date",                 "text"),
        ("fiscal_year",          "int"),
        ("eps",                  "real"),
        ("bps",                  "real"),
        ("per",                  "real"),
        ("pbr",                  "real"),
        ("roe",                  "real"),
        ("debt_ratio",           "real"),
        ("current_ratio",        "real"),
        ("revenue_growth",       "real"),
        ("operating_growth",     "real"),
        ("valuation_status",     "text"),
        ("profitability_status", "text"),
        ("growth_status",        "text"),
        ("stability_status",     "text"),
    ],
    "financial_statements": [
        ("stock_code",          "text"),
        ("fiscal_year",         "int"),
        ("reprt_code",          "text"),
        ("revenue",             "int"),
        ("operating_income",    "int"),
        ("net_income",          "int"),
        ("total_assets",        "int"),
        ("total_liabilities",   "int"),
        ("total_equity",        "int"),
        ("current_assets",      "int"),
        ("current_liabilities", "int"),
        ("shares_outstanding",  "int"),
    ],
}


def _columns(table: str) -> list[str]:
    return [c for c, _ in SCHEMA[table]]


def _decode_blob(value: bytes, expected: str):
    """`.recover` SQLite 의 BLOB → 정상 typed 값.

    sqlite3 `.recover` 가 INTEGER/REAL affinity 를 잃고 raw 8/4 byte
    little-endian BLOB 로 복구한 경우를 처리. 디코딩 불가하면 None.
    """
    n = len(value)
    if expected == "int":
        if n == 8:
            return struct.unpack("<q", value)[0]
        if n == 4:
            return struct.unpack("<i", value)[0]
        # ASCII text 로 적힌 숫자일 수도 있음 (드물지만)
        try:
            return int(value.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return None
    if expected == "real":
        if n == 8:
            return struct.unpack("<d", value)[0]
        if n == 4:
            return struct.unpack("<f", value)[0]
        try:
            return float(value.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return None
    # text
    try:
        return value.decode("utf-8")
    except UnicodeDecodeError:
        return None


def _format_row(values: tuple, schema: list[tuple[str, str]]) -> str:
    """tuple → TAB-separated text for COPY FROM STDIN.

    BLOB(bytes) 값은 schema 타입에 맞춰 디코딩. NULL → \\N.
    """
    parts = []
    for v, (_, typ) in zip(values, schema, strict=True):
        if v is None:
            parts.append(r"\N")
            continue
        if isinstance(v, bytes):
            v = _decode_blob(v, typ)
            if v is None:
                parts.append(r"\N")
                continue
        s = str(v)
        s = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")
        parts.append(s)
    return "\t".join(parts) + "\n"


def _migrate_clean(sqlite_conn, pg_raw_conn, table: str) -> int:
    """무결성 OK SQLite 파일 → 청크 SELECT + COPY."""
    schema = SCHEMA[table]
    col_csv = ", ".join(_columns(table))

    sqlite_cur = sqlite_conn.cursor()
    pg_cur = pg_raw_conn.cursor()
    sqlite_cur.execute(f"SELECT {col_csv} FROM {table}")

    copy_sql = f"COPY {table} ({col_csv}) FROM STDIN"

    total = 0
    started = time.monotonic()
    while True:
        try:
            rows = sqlite_cur.fetchmany(CHUNK_SIZE)
        except sqlite3.DatabaseError as e:
            raise RuntimeError(
                f"{table}: fetchmany 중 손상 발견 — {e}. "
                "이 파일은 손상 가능성. --recovered 로 다른 파일 지정 필요."
            ) from e
        if not rows:
            break

        buf = io.StringIO()
        for row in rows:
            buf.write(_format_row(row, schema))
        buf.seek(0)

        pg_cur.copy_expert(copy_sql, buf)
        total += len(rows)
        elapsed = time.monotonic() - started
        rate = total / elapsed if elapsed > 0 else 0
        print(f"  [{table}] {total:,} rows / {elapsed:.1f}s ({rate:.0f} rows/s)")

    pg_raw_conn.commit()
    return total


def _migrate_skip_corrupted(sqlite_conn, pg_raw_conn, table: str) -> tuple[int, int]:
    """손상 SQLite 파일 → row-by-row SELECT, 깨진 row 만 skip.

    rowid 로 순회하며 한 row 씩 fetch. 깨진 row 는 DatabaseError 라
    예외 catch 후 다음 rowid 로 계속.

    Returns: (적재된 row 수, skip된 row 수)
    """
    schema = SCHEMA[table]
    col_csv = ", ".join(_columns(table))

    sqlite_cur = sqlite_conn.cursor()
    pg_cur = pg_raw_conn.cursor()

    # rowid 범위 확인 — 깨진 row 가 있어도 MAX(rowid) 자체는 보통 읽힘
    try:
        sqlite_cur.execute(f"SELECT MIN(rowid), MAX(rowid) FROM {table}")
        rowid_min, rowid_max = sqlite_cur.fetchone()
    except sqlite3.DatabaseError as e:
        raise RuntimeError(f"{table}: MIN/MAX(rowid) 자체 실패 — {e}") from e

    if rowid_min is None:
        print(f"  [{table}] 빈 테이블")
        return 0, 0

    print(f"  [{table}] rowid {rowid_min} ~ {rowid_max} (skip 모드)")

    copy_sql = f"COPY {table} ({col_csv}) FROM STDIN"

    inserted = 0
    skipped = 0
    started = time.monotonic()
    buf = io.StringIO()
    buf_count = 0
    FLUSH_AT = 1000  # 적은 데이터라 청크 작아도 됨

    # 한 row 씩 SELECT — sqlite_cur 를 매번 새로 만들지 않아도 됨.
    fetch_cur = sqlite_conn.cursor()
    for rowid in range(rowid_min, rowid_max + 1):
        try:
            fetch_cur.execute(
                f"SELECT {col_csv} FROM {table} WHERE rowid = ?", (rowid,)
            )
            row = fetch_cur.fetchone()
        except sqlite3.DatabaseError:
            skipped += 1
            continue

        if row is None:
            continue   # rowid 가 비어있는 hole — skip 카운트 아님

        buf.write(_format_row(row, schema))
        buf_count += 1

        if buf_count >= FLUSH_AT:
            buf.seek(0)
            pg_cur.copy_expert(copy_sql, buf)
            inserted += buf_count
            buf = io.StringIO()
            buf_count = 0

    if buf_count > 0:
        buf.seek(0)
        pg_cur.copy_expert(copy_sql, buf)
        inserted += buf_count

    pg_raw_conn.commit()
    elapsed = time.monotonic() - started
    print(f"  [{table}] inserted={inserted:,} skipped={skipped} / {elapsed:.1f}s")
    return inserted, skipped


def _all_tables() -> list[str]:
    return ["daily_ohlcv", "daily_fundamentals", "daily_indicators", "financial_statements"]


def _table_counts(pg_raw_conn) -> dict[str, int]:
    pg_cur = pg_raw_conn.cursor()
    out = {}
    for t in _all_tables():
        pg_cur.execute(f"SELECT COUNT(*) FROM {t}")
        out[t] = pg_cur.fetchone()[0]
    return out


def migrate(recovered_path: str, main_path: str, truncate: bool = False) -> None:
    """A안 — 깨끗한 recovered.db 에서 OHLCV/fundamentals, 손상된 main.db 에서 statements."""
    if not os.path.exists(recovered_path):
        raise FileNotFoundError(f"recovered 파일 없음: {recovered_path}")
    if not os.path.exists(main_path):
        raise FileNotFoundError(f"main(손상) 파일 없음: {main_path}")

    print(f"[START] migration (A안)")
    print(f"  recovered (clean): {recovered_path}")
    print(f"  main (corrupted) : {main_path}")

    pg_raw_conn = get_engine().raw_connection()
    try:
        before = _table_counts(pg_raw_conn)
        print("[PRE] Postgres row count: " + ", ".join(
            f"{t}={c:,}" for t, c in before.items()
        ))

        if truncate:
            pg_cur = pg_raw_conn.cursor()
            pg_cur.execute(f"TRUNCATE {', '.join(_all_tables())}")
            pg_raw_conn.commit()
            print("[TRUNCATE] 4 테이블 비움")
        else:
            non_empty = [t for t, c in before.items() if c > 0]
            if non_empty:
                raise RuntimeError(
                    f"Postgres 측에 이미 데이터 있음: {non_empty}. "
                    "--truncate 로 비우고 재실행."
                )

        # Phase 1: clean source 에서 OHLCV + fundamentals
        print("\n=== Phase 1: recovered.db (clean) → daily_ohlcv + daily_fundamentals ===")
        clean_conn = sqlite3.connect(recovered_path)
        try:
            for table in ("daily_ohlcv", "daily_fundamentals"):
                print(f"\n--- {table} ---")
                _migrate_clean(clean_conn, pg_raw_conn, table)
        finally:
            clean_conn.close()

        # Phase 2: corrupted source 에서 financial_statements (skip 모드)
        print("\n=== Phase 2: stock_master.db (corrupted) → financial_statements (skip 모드) ===")
        corrupt_conn = sqlite3.connect(main_path)
        try:
            _migrate_skip_corrupted(corrupt_conn, pg_raw_conn, "financial_statements")
        finally:
            corrupt_conn.close()

        # Phase 3: daily_indicators 는 Postgres daily_ohlcv 로 재계산 안내
        print("\n=== Phase 3: daily_indicators — SKIP ===")
        print("  손상되지 않은 SQLite source 없음. Postgres daily_ohlcv 적재 완료 후:")
        print("  $ python -m scripts.backfill.historical_indicator_loader")
        print("  로 재계산 (Postgres 기반).")

        after = _table_counts(pg_raw_conn)
        print("\n[POST] Postgres row count: " + ", ".join(
            f"{t}={c:,}" for t, c in after.items()
        ))
        print(f"\n[FIN] migration 완료 — daily_indicators 재계산 남음")
    finally:
        pg_raw_conn.close()


def main():
    parser = argparse.ArgumentParser(
        description="SQLite → Postgres migration (A안: clean recovered.db + skip-corrupted main.db)",
    )
    parser.add_argument("--recovered", default=_DEFAULT_RECOVERED,
                        help=f"clean SQLite (default: {_DEFAULT_RECOVERED})")
    parser.add_argument("--main", default=_DEFAULT_MAIN,
                        help=f"corrupted SQLite for financial_statements (default: {_DEFAULT_MAIN})")
    parser.add_argument("--truncate", action="store_true",
                        help="Postgres 측 4 테이블 TRUNCATE 후 적재")
    args = parser.parse_args()

    migrate(args.recovered, args.main, truncate=args.truncate)


if __name__ == "__main__":
    main()
