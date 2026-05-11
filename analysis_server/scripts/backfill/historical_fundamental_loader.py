"""historical_fundamental_loader.py

학습용 fundamental panel 데이터 적재 파이프라인.
OpenDART 사업보고서 → financial_statements (원본 캐시)
financial_statements + daily_ohlcv → daily_fundamentals ((stock_code, date) panel)

실시간 분석용 fundamental_calculator.py 와는 의도가 다르다:
 - fundamental_calculator.py : KIS 실시간 가격 + 직전 사업연도 → JSON 응답
 - historical_fundamental_loader.py : DART 다년치 사업보고서 + 과거 종가 → 학습용 panel

Point-in-time 원칙:
 - 사업보고서는 사업연도 종료 후 90일 이내(통상 익년 3월말) 공시 의무
 - 보수적으로 익년 4월 1일 이후부터 해당 보고서가 "사용 가능"하다고 본다
 - → 2024-03-15 시점에는 2022 사업보고서가 최신, 2024-04-01부터 2023 사업보고서 사용

종목 universe & 상장 변동:
 - 1차 universe: dart_corp_code.json (6자리 stock_code → 8자리 corp_code 매핑) — DART 공시 회사
 - 2차 필터: daily_ohlcv 에 학습 구간 데이터가 있는 종목만 처리
 - 두 집합의 교집합으로 corp_code 매핑 부재 / OHLCV 부재 종목을 사전 차단
 - 종목별로 OHLCV 가용 구간을 보고 필요한 fiscal_year 만 DART에 호출 (quota 절약)
 - daily_ohlcv 의 stock_code 가 leading zero 없이 저장된 경우에도 zfill(6) 정규화로 매칭

API 중단 복구:
 - 매 호출 직후 commit + (stock, fy) 충돌 시 skip → 강제 종료/재시작 자유
 - DART quota(status=020) 도달 시 stdout 에 status=020 노출 → Ctrl+C 후
   .env 의 DART_API_KEY 교체 → 재실행하면 캐시 hit으로 이어서 진행
"""

import json
import os
import sqlite3
import time
import pandas as pd
from datetime import datetime

from dart_api_client import DartApiClient, DartCriticalError
from fundamental_calculator import (
    classify_valuation,
    classify_profitability,
    classify_growth,
    classify_stability,
)


# ---------- 스키마 ----------

CREATE_FINANCIAL_STATEMENTS_SQL = """
CREATE TABLE IF NOT EXISTS financial_statements (
    stock_code           TEXT NOT NULL,
    fiscal_year          INTEGER NOT NULL,
    reprt_code           TEXT NOT NULL DEFAULT '11011',  -- 사업보고서
    revenue              INTEGER,   -- 매출액
    operating_income     INTEGER,   -- 영업이익
    net_income           INTEGER,   -- 당기순이익
    total_assets         INTEGER,   -- 자산총계
    total_liabilities    INTEGER,   -- 부채총계
    total_equity         INTEGER,   -- 자본총계
    current_assets       INTEGER,   -- 유동자산
    current_liabilities  INTEGER,   -- 유동부채
    shares_outstanding   INTEGER,   -- 보통주 발행주식수
    fetched_at           TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (stock_code, fiscal_year, reprt_code)
);
"""

CREATE_DAILY_FUNDAMENTALS_SQL = """
CREATE TABLE IF NOT EXISTS daily_fundamentals (
    stock_code           TEXT NOT NULL,
    date                 TEXT NOT NULL,
    fiscal_year          INTEGER,           -- 해당 시점에 사용된 사업연도 (point-in-time)
    -- per-share
    eps                  REAL,
    bps                  REAL,
    -- valuation (가격 의존, 매일 변동)
    per                  REAL,
    pbr                  REAL,
    -- profitability
    roe                  REAL,              -- %
    -- stability ratios
    debt_ratio           REAL,              -- 부채총계 / 자본총계 * 100
    current_ratio        REAL,              -- 유동자산 / 유동부채 * 100
    -- growth (YoY)
    revenue_growth       REAL,              -- (curr-prev)/|prev| * 100
    operating_growth     REAL,              -- 동일 공식
    -- 분류 (fundamental_calculator.py 룰 그대로)
    valuation_status     TEXT,              -- undervalued / fair / overvalued / unknown
    profitability_status TEXT,              -- high_margin / normal / low_margin / unknown
    growth_status        TEXT,              -- high_growth / steady_growth / stagnant / declining / unknown
    stability_status     TEXT,              -- stable / moderate / risky / unknown
    PRIMARY KEY (stock_code, date)
);
"""

CREATE_INDEX_FUNDAMENTALS_DATE = (
    "CREATE INDEX IF NOT EXISTS idx_daily_fundamentals_date "
    "ON daily_fundamentals(date);"
)


# ---------- Point-in-time 헬퍼 ----------

def pick_fiscal_year(date_str):
    """주어진 날짜에 '공시되어 있을' 가장 최근 사업연도를 반환.

    사업보고서는 통상 익년 3월말 공시 → 보수적으로 4월 1일부터 사용 가능으로 가정.
    fundamental_calculator.py:108-110 의 휴리스틱과 동일.
    """
    dt = datetime.strptime(date_str, "%Y-%m-%d")
    return dt.year - 1 if dt.month >= 4 else dt.year - 2


CORP_CODE_JSON = "dart_corp_code.json"


def _load_corp_code_map():
    """dart_corp_code.json 직접 로드 — {6자리 stock_code: 8자리 corp_code}.

    파일이 없으면 DartApiClient 가 OpenDART corpCode.xml 을 다운로드해 캐시한다.
    """
    if not os.path.exists(CORP_CODE_JSON):
        # DartApiClient 인스턴스화 시점에 캐시 생성됨 (단, 호출 1회 필요)
        DartApiClient()._load_corp_code_map()

    with open(CORP_CODE_JSON, "r", encoding="utf-8") as f:
        return json.load(f)


def _get_stock_fiscal_year_map(conn, start_date, end_date, stock_codes=None):
    """corp_code 매핑 ∩ daily_ohlcv 구간 데이터 ⇒ 종목별 처리 정보.

    반환 형태: {canonical_6digit_stock_code: {"corp_code", "db_code", "fiscal_years"}}
      - canonical_6digit_stock_code : dart_corp_code.json 기준 6자리 zero-padded
      - corp_code  : 8자리 — DART API 호출용
      - db_code    : daily_ohlcv 의 원본 stock_code (leading zero 누락된 형식 그대로)
      - fiscal_years : 종목 listing window 에서 산출한 필요 연도 리스트 (YoY 위해 -1 포함)

    daily_ohlcv 에 leading zero 없이 저장된 경우에도 zfill(6) 정규화로 매칭한다.

    Args:
        stock_codes : 명시 리스트 (6자리/짧은 형식 모두 허용 — 내부에서 zfill).
                      None이면 dart_corp_code.json 의 모든 상장 종목.
    """
    corp_map = _load_corp_code_map()

    # 1차: dart_corp_code.json 에서 universe 좁히기
    if stock_codes is not None:
        canonical_set = {str(s).zfill(6) for s in stock_codes}
        target = {sc: cc for sc, cc in corp_map.items() if sc in canonical_set}
    else:
        target = corp_map

    if not target:
        return {}

    # 2차: daily_ohlcv listing window 조회 — DB 원본 코드 → canonical 6자리 정규화
    rows = conn.execute(
        "SELECT stock_code, MIN(date), MAX(date) FROM daily_ohlcv "
        "WHERE date BETWEEN ? AND ? GROUP BY stock_code",
        (start_date, end_date),
    ).fetchall()

    ohlcv_window = {}   # canonical 6자리 → (db_code, min_d, max_d)
    for raw_code, min_d, max_d in rows:
        ohlcv_window[str(raw_code).zfill(6)] = (raw_code, min_d, max_d)

    # 교집합 — corp_map ∩ daily_ohlcv
    fy_map = {}
    for stock_code, corp_code in target.items():
        ohlcv_entry = ohlcv_window.get(stock_code)
        if ohlcv_entry is None:
            continue
        db_code, min_d, max_d = ohlcv_entry
        fy_min = pick_fiscal_year(min_d) - 1   # YoY 보정
        fy_max = pick_fiscal_year(max_d)
        if fy_min > fy_max:
            continue
        fy_map[stock_code] = {
            "corp_code": corp_code,
            "db_code": db_code,
            "fiscal_years": list(range(fy_min, fy_max + 1)),
        }

    return fy_map


# ---------- Phase 1: DART → financial_statements ----------

def fetch_annual_statements(
    db_path="../data/stock_master.db",
    start_date="2023-01-01",
    end_date="2025-12-31",
    stock_codes=None,
    sleep_between=0.5,
):
    """DART API로 사업보고서를 조회해 financial_statements 에 적재.

    - 종목 universe: daily_ohlcv 에 [start_date, end_date] 구간 데이터가 있는 종목 전체
      (stock_master.is_active 무관 — 상장 폐지 / 신규 상장 종목 자동 포함).
    - 종목별 fiscal_year: 종목의 OHLCV 가용 구간 기반으로 필요한 연도만 호출.
    - (stock_code, fiscal_year) 가 이미 적재되어 있으면 skip → 재실행 idempotent.

    API quota(status=020) 소진 감지 시:
      stdout 에 status=020 노출 → Ctrl+C → .env 의 DART_API_KEY 교체 → 재실행
      이전까지 적재된 분은 캐시 hit으로 즉시 통과.
    """
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()
    cursor.execute(CREATE_FINANCIAL_STATEMENTS_SQL)
    conn.commit()

    fy_map = _get_stock_fiscal_year_map(conn, start_date, end_date, stock_codes)
    if not fy_map:
        print("[WARN] dart_corp_code.json ∩ daily_ohlcv 교집합에 해당 구간 종목이 없습니다.")
        print("       원인 후보:")
        print("        - daily_ohlcv 의 stock_code 가 6자리 형식이 아님 (leading zero 누락 등)")
        print("        - 학습 구간 [start_date, end_date] 안에 OHLCV 데이터 부재")
        print("        - 명시한 stock_codes 가 dart_corp_code.json 에 없음 (비상장/매핑 누락)")
        conn.close()
        return

    stock_list = sorted(fy_map.keys())
    total_targets = sum(len(info["fiscal_years"]) for info in fy_map.values())
    total_stocks = len(stock_list)

    dart = DartApiClient()
    fetched = skipped = failed = 0
    critical_error = None

    print(f"[START] DART 사업보고서 적재 — {total_stocks}개 종목, "
          f"누적 호출 후보 {total_targets}건 (구간: {start_date} ~ {end_date})")

    try:
        for s_idx, stock_code in enumerate(stock_list, start=1):
            info = fy_map[stock_code]
            corp_code = info["corp_code"]   # 8자리 — DART API 호출용
            years = info["fiscal_years"]

            stock_fetched = stock_skipped = stock_failed = 0

            print(f"[{s_idx}/{total_stocks}] {stock_code} (corp={corp_code}) fy={years[0]}~{years[-1]}")

            for year in years:
                existing = cursor.execute(
                    "SELECT 1 FROM financial_statements "
                    "WHERE stock_code = ? AND fiscal_year = ? AND reprt_code = '11011'",
                    (stock_code, year),
                ).fetchone()
                if existing:
                    skipped += 1
                    stock_skipped += 1
                    continue

                try:
                    accounts = dart.get_financial_accounts(corp_code, year)
                    if not accounts:
                        # dart_api_client 가 직전에 [ERROR] status 출력함 — 여기서는 종목 매핑 추가
                        print(f"   ↳ {stock_code}/{year} 미공시 처리")
                        failed += 1
                        stock_failed += 1
                        time.sleep(sleep_between)
                        continue

                    shares = dart.get_shares_outstanding(corp_code, year)

                    cursor.execute(
                        """
                        REPLACE INTO financial_statements
                        (stock_code, fiscal_year, reprt_code,
                         revenue, operating_income, net_income,
                         total_assets, total_liabilities, total_equity,
                         current_assets, current_liabilities, shares_outstanding)
                        VALUES (?, ?, '11011', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        (
                            stock_code, year,   # canonical 6자리 stock_code 로 적재
                            accounts.get("매출액"),
                            accounts.get("영업이익"),
                            accounts.get("당기순이익"),
                            accounts.get("자산총계"),
                            accounts.get("부채총계"),
                            accounts.get("자본총계"),
                            accounts.get("유동자산"),
                            accounts.get("유동부채"),
                            shares,
                        ),
                    )
                    conn.commit()
                    fetched += 1
                    stock_fetched += 1
                    time.sleep(sleep_between)

                except DartCriticalError:
                    raise  # 즉시 외부 try 로 전파 → 사유별 안내 후 종료
                except Exception as e:
                    print(f"[ERROR] {stock_code}/{corp_code}/{year}: {e}")
                    failed += 1
                    stock_failed += 1
                    time.sleep(sleep_between)

            print(f"   → {stock_code} 완료 (fetched={stock_fetched}, "
                  f"skipped={stock_skipped}, failed={stock_failed})")

            if s_idx % 50 == 0 or s_idx == total_stocks:
                print(f"\n[{s_idx}/{total_stocks}] 누적 — "
                      f"fetched={fetched}, skipped={skipped}, failed={failed}\n")

    except DartCriticalError as e:
        critical_error = e
    finally:
        conn.close()

    if critical_error is not None:
        e = critical_error
        print(f"\n[STOP] DART 작업 중단 — 직전까지 적재분: "
              f"fetched={fetched}, skipped={skipped}, failed={failed}")
        if e.status == "020":
            print(f"       원인: 일일 호출 한도 초과 (status=020, message={e.message})")
            print(f"       조치: .env 의 DART_API_KEY 를 다른 팀원 키로 교체 후 같은 명령 재실행")
            print(f"             이미 적재된 행은 캐시 hit 으로 자동 skip — 중복 호출 없음")
        elif e.status in ("010", "011"):
            print(f"       원인: API 키 인증 실패 (status={e.status}, message={e.message})")
            print(f"       조치: .env 의 DART_API_KEY 가 유효한지 확인 후 재실행")
        elif e.status == "012":
            print(f"       원인: 허용되지 않은 IP (status=012, message={e.message})")
            print(f"       조치: OpenDART 콘솔에서 등록 IP 확인 후 재실행")
        else:
            print(f"       원인: 치명적 응답 (status={e.status}, message={e.message})")
        return

    print(f"[FIN] DART 적재 완료 — fetched={fetched}, skipped={skipped}, failed={failed}")


# ---------- Phase 2: financial_statements + daily_ohlcv → daily_fundamentals ----------

_ACCOUNT_KEY_MAP = {
    "revenue":             "매출액",
    "operating_income":    "영업이익",
    "net_income":          "당기순이익",
    "total_assets":        "자산총계",
    "total_liabilities":   "부채총계",
    "total_equity":        "자본총계",
    "current_assets":      "유동자산",
    "current_liabilities": "유동부채",
}


def _row_to_accounts(row):
    """financial_statements 행을 fundamental_calculator 가 기대하는 dict 형태로 변환."""
    if row is None:
        return None
    return {ko: row[col] for col, ko in _ACCOUNT_KEY_MAP.items()}


def _compute_one_row(stock_code, date, close, curr_row, prev_row):
    """단일 (stock, date) 행에 대해 모든 fundamental 지표 계산."""
    if curr_row is None:
        return None

    curr = _row_to_accounts(curr_row)
    prev = _row_to_accounts(prev_row) if prev_row is not None else None

    ni     = curr_row["net_income"]
    equity = curr_row["total_equity"]
    debt   = curr_row["total_liabilities"]
    ca     = curr_row["current_assets"]
    cl     = curr_row["current_liabilities"]
    shares = curr_row["shares_outstanding"]

    # per-share — fundamental_calculator.py:143-149 와 동일 로직
    eps = (ni / shares) if (ni is not None and shares and shares > 0) else None
    bps = (equity / shares) if (equity is not None and shares and shares > 0) else None
    per = (close / eps) if (eps is not None and eps > 0 and close is not None) else None
    pbr = (close / bps) if (bps is not None and bps > 0 and close is not None) else None
    roe = (ni / equity * 100) if (ni is not None and equity is not None and equity > 0) else None

    debt_ratio = (debt / equity * 100) if (debt is not None and equity is not None and equity > 0) else None
    current_ratio = (ca / cl * 100) if (ca is not None and cl is not None and cl > 0) else None

    rev_growth = None
    op_growth = None
    if prev is not None:
        rev_curr, rev_prev = curr.get("매출액"), prev.get("매출액")
        if rev_curr is not None and rev_prev:
            rev_growth = (rev_curr - rev_prev) / abs(rev_prev) * 100
        op_curr, op_prev = curr.get("영업이익"), prev.get("영업이익")
        if op_curr is not None and op_prev:
            op_growth = (op_curr - op_prev) / abs(op_prev) * 100

    val_status  = classify_valuation(per, pbr)
    prof_status = classify_profitability(roe)
    grow_status = classify_growth(curr, prev)
    stab_status = classify_stability(curr)

    def _r(v):
        return round(v, 2) if v is not None else None

    return (
        stock_code, date, curr_row["fiscal_year"],
        _r(eps), _r(bps), _r(per), _r(pbr), _r(roe),
        _r(debt_ratio), _r(current_ratio),
        _r(rev_growth), _r(op_growth),
        val_status, prof_status, grow_status, stab_status,
    )


DAILY_FUND_INSERT_SQL = """
REPLACE INTO daily_fundamentals
(stock_code, date, fiscal_year,
 eps, bps, per, pbr, roe,
 debt_ratio, current_ratio,
 revenue_growth, operating_growth,
 valuation_status, profitability_status, growth_status, stability_status)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
"""


def build_daily_fundamentals(
    db_path="../data/stock_master.db",
    start_date="2023-01-01",
    end_date="2025-12-31",
    stock_codes=None,
):
    """financial_statements + daily_ohlcv → daily_fundamentals panel 적재."""
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()
    cursor.execute(CREATE_DAILY_FUNDAMENTALS_SQL)
    cursor.execute(CREATE_INDEX_FUNDAMENTALS_DATE)
    conn.commit()

    fy_map = _get_stock_fiscal_year_map(conn, start_date, end_date, stock_codes)
    if not fy_map:
        print("[WARN] dart_corp_code.json ∩ daily_ohlcv 교집합에 해당 구간 종목이 없습니다.")
        conn.close()
        return

    stock_list = sorted(fy_map.keys())
    total = len(stock_list)
    print(f"[START] daily_fundamentals panel 적재 — {total}개 종목 ({start_date} ~ {end_date})")

    inserted_total = 0
    skipped_no_stmt = 0
    skipped_no_ohlcv = 0
    failed = 0

    for idx, stock_code in enumerate(stock_list, start=1):
        try:
            db_code = fy_map[stock_code]["db_code"]   # daily_ohlcv 원본 형식

            # canonical 6자리로 financial_statements 조회
            stmts = pd.read_sql(
                """
                SELECT fiscal_year, revenue, operating_income, net_income,
                       total_assets, total_liabilities, total_equity,
                       current_assets, current_liabilities, shares_outstanding
                FROM financial_statements
                WHERE stock_code = ? AND reprt_code = '11011'
                """,
                conn, params=(stock_code,),
            )
            if stmts.empty:
                skipped_no_stmt += 1
                continue

            stmts_by_year = {row["fiscal_year"]: row for _, row in stmts.iterrows()}

            # daily_ohlcv 는 db_code(원본 형식)로 조회
            ohlcv = pd.read_sql(
                """
                SELECT date, close FROM daily_ohlcv
                WHERE stock_code = ? AND date BETWEEN ? AND ?
                ORDER BY date ASC
                """,
                conn, params=(db_code, start_date, end_date),
            )
            if ohlcv.empty:
                skipped_no_ohlcv += 1
                continue

            tuples = []
            for _, ohlcv_row in ohlcv.iterrows():
                date = ohlcv_row["date"]
                close = ohlcv_row["close"]
                fy = pick_fiscal_year(date)

                curr_row = stmts_by_year.get(fy)
                prev_row = stmts_by_year.get(fy - 1)
                # daily_fundamentals 도 canonical 6자리로 적재
                computed = _compute_one_row(stock_code, date, close, curr_row, prev_row)
                if computed is not None:
                    tuples.append(computed)

            if tuples:
                cursor.executemany(DAILY_FUND_INSERT_SQL, tuples)
                conn.commit()
                inserted_total += len(tuples)

            if idx % 25 == 0 or idx == total:
                print(f"[{idx}/{total}] 진행 — 누적 {inserted_total}건 적재 "
                      f"(stmt없음 {skipped_no_stmt}, ohlcv없음 {skipped_no_ohlcv}, fail {failed})")

        except Exception as e:
            failed += 1
            print(f"[ERROR] {stock_code}: {e}")

    conn.close()
    print(f"[FIN] daily_fundamentals 적재 — 총 {inserted_total}건 / "
          f"stmt없음 {skipped_no_stmt} / ohlcv없음 {skipped_no_ohlcv} / fail {failed}")


# ---------- 통합 진입점 ----------

def run_full_pipeline(
    db_path="../data/stock_master.db",
    start_date="2023-01-01",
    end_date="2025-12-31",
    stock_codes=None,
    sleep_between=0.5,
):
    """Phase 1 (DART fetch) → Phase 2 (daily panel build) 통합 실행."""
    fetch_annual_statements(
        db_path=db_path,
        start_date=start_date,
        end_date=end_date,
        stock_codes=stock_codes,
        sleep_between=sleep_between,
    )
    build_daily_fundamentals(
        db_path=db_path,
        start_date=start_date,
        end_date=end_date,
        stock_codes=stock_codes,
    )


if __name__ == "__main__":
    # 23~25년 train(23-24) + test(25) panel 적재
    run_full_pipeline(
        start_date="2023-01-01",
        end_date="2025-12-31",
    )
