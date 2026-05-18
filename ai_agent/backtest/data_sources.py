"""백테스트 데이터 접근 — Postgres(시장·지표·재무) + Mongo(공시·뉴스).

설계 의도:
  - 모든 쿼리는 as_of 시점 이전 데이터만 본다 (룩어헤드 회피).
  - 시장 데이터 4 테이블은 백엔드 마이그레이션 후의 Postgres 스키마를 가정 —
    DDL 은 PR 본문 참고 (daily_ohlcv / daily_fundamentals / daily_indicators /
    financial_statements).
  - 공시는 modu_mongo.disclosures (historical_disclosure_loader 백필 + 라이브
    영속화). 뉴스는 modu_mongo.news_articles.
  - 가공은 signal_generator 가 담당 — 여기서는 raw row/document 만 반환.

성능 메모:
  - 영업일 1 회 호출당 watchlist 전 종목 데이터를 한 번에 가져와 메모리에 캐싱하는
    'bulk pre-fetch' 가 영업일 약 500 일 × 종목 수천 시나리오에서 필수.
  - 종목별 N+1 쿼리 패턴은 24-25 년이면 수 시간 차이 — fetch_*_by_date 사용 권장.
"""

from __future__ import annotations

import logging
from contextlib import contextmanager
from datetime import date, datetime, timedelta
from typing import Iterator, Optional
from zoneinfo import ZoneInfo

from pymongo import MongoClient
from sqlalchemy import bindparam, create_engine, text
from sqlalchemy.engine import Engine

from . import config

logger = logging.getLogger(__name__)
KST = ZoneInfo("Asia/Seoul")


# ─── SQLite/Postgres dialect 공용 헬퍼 ───────────────────────────────────────
# SQLite 는 DATE 컬럼을 TEXT('YYYY-MM-DD') 로 저장·반환. Postgres 는 date 객체.
# 두 환경에서 동일하게 동작하려면 input bind 는 isoformat string, output 은 date.

def _to_date(v) -> Optional[date]:
    if v is None or isinstance(v, date):
        return v if not isinstance(v, datetime) else v.date()
    if isinstance(v, str):
        return datetime.strptime(v[:10], "%Y-%m-%d").date()
    raise TypeError(f"can't coerce to date: {type(v).__name__}")


def _d(day: date) -> str:
    """date bind 파라미터 통일 — SQLite 비교가 string lexicographic 으로 정확."""
    return day.isoformat()


# ─── 연결 ─────────────────────────────────────────────────────────────────────

def make_engine(env: config.BacktestEnv) -> Engine:
    """SQLAlchemy Engine — Postgres (ai_agent.user_context.create_engine_from_env 패턴).

    SQLite 로 롤백할 때는 아래 주석 블록 활성화 + Postgres 라인 주석 처리.
    """
    return create_engine(env.postgres_dsn, pool_pre_ping=True)

    # ── SQLite 롤백 시 복구 ────────────────────────────────────────────────
    # return create_engine(
    #     f"sqlite:///{env.sqlite_path}",
    #     connect_args={"check_same_thread": False},
    # )


@contextmanager
def mongo_client(env: config.BacktestEnv) -> Iterator[MongoClient]:
    """Mongo 클라이언트 컨텍스트 — 종료 시 자동 close."""
    # backtest 중 wifi가 잠시 끊겨도 견디게 — serverSelection / socket timeout 늘리고
    # retryReads 활성화. backtest는 Mongo를 read-only로 쓰지만 retryWrites도 켜둠.
    client = MongoClient(
        env.mongo_uri,
        serverSelectionTimeoutMS=30000,
        socketTimeoutMS=60000,
        retryReads=True,
        retryWrites=True,
    )
    try:
        client.admin.command("ping")
        yield client
    finally:
        client.close()


# ─── Postgres: 영업일 ─────────────────────────────────────────────────────────

def fetch_trading_days(engine: Engine, start: date, end: date) -> list[date]:
    """daily_ohlcv 에서 실제 데이터가 있는 거래일 목록 (오름차순).

    공휴일·임시휴장을 별도 캘린더 없이 자동 회피. start/end 포함.
    """
    with engine.connect() as conn:
        rows = conn.execute(
            text("""
                SELECT DISTINCT date FROM daily_ohlcv
                WHERE date BETWEEN :start AND :end
                ORDER BY date
            """),
            {"start": _d(start), "end": _d(end)},
        ).all()
    return [_to_date(r[0]) for r in rows]


# ─── Postgres: watchlist ─────────────────────────────────────────────────────

def fetch_watchlist_on(engine: Engine, day: date) -> list[str]:
    """해당 일자 daily_ohlcv 에 거래 기록이 있는 종목 코드 (살아 있는 종목).

    상장폐지된 종목이 24년 이후 사라지므로 매일 watchlist 를 새로 산출하면
    survivorship bias 회피.
    """
    with engine.connect() as conn:
        rows = conn.execute(
            text("SELECT stock_code FROM daily_ohlcv WHERE date = :d"),
            {"d": _d(day)},
        ).all()
    return [r[0] for r in rows]


# ─── Postgres: 시점별 bulk fetch ──────────────────────────────────────────────

def fetch_ohlcv_by_date(engine: Engine, day: date) -> dict[str, dict]:
    """stock_code → daily_ohlcv 한 row(dict). 해당 일자 전체 종목."""
    with engine.connect() as conn:
        result = conn.execute(
            text("""
                SELECT stock_code, date, open, high, low, close, volume
                FROM daily_ohlcv WHERE date = :d
            """),
            {"d": _d(day)},
        ).mappings().all()
    return {r["stock_code"]: dict(r) for r in result}


def fetch_indicators_by_date(engine: Engine, day: date,
                              include_prev: bool = True) -> dict[str, dict]:
    """daily_indicators 해당 일 전 종목 + 직전 영업일 row(rsi/mfi prev 용).

    include_prev=True 면 같은 stock_code 의 가장 가까운 이전 영업일 row 를
    `_prev` 접두 컬럼으로 병합 — detection_engine 의 RSI-003/004 등 prev 필요 룰
    평가에 필요.
    """
    with engine.connect() as conn:
        current = conn.execute(
            text("SELECT * FROM daily_indicators WHERE date = :d"),
            {"d": _d(day)},
        ).mappings().all()
        result: dict[str, dict] = {r["stock_code"]: dict(r) for r in current}

        if include_prev and result:
            stocks = list(result.keys())

            # Postgres: DISTINCT ON 으로 종목별 가장 가까운 이전 영업일 row 한 번에.
            prev_rows = conn.execute(
                text("""
                    SELECT DISTINCT ON (stock_code)
                        stock_code, rsi_14 AS rsi_14_prev, mfi_14 AS mfi_14_prev,
                        bollinger_position AS bollinger_position_prev,
                        sma_alignment AS sma_alignment_prev,
                        date AS prev_date
                    FROM daily_indicators
                    WHERE date < :d AND stock_code = ANY(:stocks)
                    ORDER BY stock_code, date DESC
                """),
                {"d": _d(day), "stocks": stocks},
            ).mappings().all()

            # ── SQLite 롤백 시 복구 (DISTINCT ON 미지원 → MAX subquery, ANY → IN) ─
            # stmt = text("""
            #     SELECT t.stock_code,
            #            t.rsi_14            AS rsi_14_prev,
            #            t.mfi_14            AS mfi_14_prev,
            #            t.bollinger_position AS bollinger_position_prev,
            #            t.sma_alignment     AS sma_alignment_prev,
            #            t.date              AS prev_date
            #     FROM daily_indicators t
            #     INNER JOIN (
            #         SELECT stock_code, MAX(date) AS max_date
            #         FROM daily_indicators
            #         WHERE date < :d AND stock_code IN :stocks
            #         GROUP BY stock_code
            #     ) m
            #       ON t.stock_code = m.stock_code AND t.date = m.max_date
            # """).bindparams(bindparam("stocks", expanding=True))
            # prev_rows = conn.execute(stmt, {"d": _d(day), "stocks": stocks}).mappings().all()

            for prev in prev_rows:
                sc = prev["stock_code"]
                if sc in result:
                    result[sc].update({k: v for k, v in prev.items() if k != "stock_code"})
    return result


def fetch_fundamentals_by_date(engine: Engine, day: date) -> dict[str, dict]:
    """daily_fundamentals 해당 일 전 종목.

    fundamental_loader 가 매일 적재 가정. 일자별 누락이 있으면 가장 가까운
    이전 영업일 row 로 fallback (분기 결산 직전 등에 stale 한 점은 라이브와 동일).
    """
    with engine.connect() as conn:
        current = conn.execute(
            text("SELECT * FROM daily_fundamentals WHERE date = :d"),
            {"d": _d(day)},
        ).mappings().all()
        if current:
            return {r["stock_code"]: dict(r) for r in current}

        # fallback — 가장 가까운 이전 일자.
        # Postgres: DISTINCT ON 으로 종목별 최신 row 한 번에.
        rows = conn.execute(
            text("""
                SELECT DISTINCT ON (stock_code) *
                FROM daily_fundamentals
                WHERE date <= :d
                ORDER BY stock_code, date DESC
            """),
            {"d": _d(day)},
        ).mappings().all()

        # ── SQLite 롤백 시 복구 (MAX subquery) ────────────────────────────
        # rows = conn.execute(
        #     text("""
        #         SELECT t.*
        #         FROM daily_fundamentals t
        #         INNER JOIN (
        #             SELECT stock_code, MAX(date) AS max_date
        #             FROM daily_fundamentals
        #             WHERE date <= :d
        #             GROUP BY stock_code
        #         ) m
        #           ON t.stock_code = m.stock_code AND t.date = m.max_date
        #     """),
        #     {"d": _d(day)},
        # ).mappings().all()
        return {r["stock_code"]: dict(r) for r in rows}


# ─── Mongo: 공시 ─────────────────────────────────────────────────────────────

def fetch_disclosures_window(
    client: MongoClient, day: date, stock_codes: list[str],
    lookback_days: int = config.EVENT_LOOKBACK_DAYS,
) -> dict[str, list[dict]]:
    """as_of day 기준 최근 lookback_days 일치 공시. stock_code 별 그룹.

    rcept_date 는 BSON Date(UTC) — KST tz-aware datetime 으로 비교.
    live disclosure_collector 의 fetch_recent_disclosures(LOOKBACK_DAYS=2) 와
    동일 윈도우. stock_code 가 None 인(비상장) 문서는 자동 제외.
    """
    end_dt = datetime.combine(day, datetime.min.time(), tzinfo=KST) + timedelta(days=1)
    start_dt = end_dt - timedelta(days=lookback_days + 1)

    coll = client[config.MONGO_DB][config.MONGO_DISCLOSURE_COLL]
    cursor = coll.find({
        "stock_code": {"$in": stock_codes},
        "rcept_date": {"$gte": start_dt, "$lt": end_dt},
    })

    grouped: dict[str, list[dict]] = {}
    for doc in cursor:
        sc = doc.get("stock_code")
        if not sc:
            continue
        grouped.setdefault(sc, []).append(doc)
    return grouped


# ─── Mongo: 뉴스 (sentiment 빌더의 raw 입력) ──────────────────────────────────

def fetch_news_window(
    client: MongoClient, day: date, stock_codes: list[str],
    lookback_days: int = config.SENTIMENT_LOOKBACK_DAYS,
) -> dict[str, list[dict]]:
    """as_of day 기준 최근 lookback_days 일치 뉴스 전체 반환. stock_codes 매칭.

    실 서비스 5-1 '가장 마지막으로 계산된 감성 지수' 재현을 위해 30일 윈도우 사용.
    signal_generator / always_trigger 의 _to_sentiment 가 published_at 내림차순
    정렬 후 최신 1건을 취하므로, 윈도우 내 뉴스가 없어도 None 처리 보장.
    FinBERT 점수 필드(sentiment_score, confidence, neg_prob, pos_prob, neu_prob)
    가 적재된 상태여야 sentiment payload 빌드 가능.
    """
    # end_dt = day 익일 00:00 KST (배타적), start_dt = end_dt - lookback_days.
    # 윈도우 길이 = lookback_days 일자 (예: lookback=1 → 당일 1 일치만).
    end_dt = datetime.combine(day, datetime.min.time(), tzinfo=KST) + timedelta(days=1)
    start_dt = end_dt - timedelta(days=lookback_days)

    coll = client[config.MONGO_DB][config.MONGO_NEWS_COLL]
    # news_articles 의 published_at 은 naive ISO 문자열로 저장되므로 (news_collector
    # 의 _normalize_published_at 결과) tz-aware 와 lexicographic 비교가 어긋난다.
    # 두 경계를 모두 naive(KST wall-clock) 로 통일해 비교 일관성 확보.
    start_str = start_dt.replace(tzinfo=None).isoformat()
    end_str = end_dt.replace(tzinfo=None).isoformat()
    cursor = coll.find({
        "stock_codes": {"$in": stock_codes},
        "published_at": {"$gte": start_str, "$lt": end_str},
    })

    grouped: dict[str, list[dict]] = {}
    for doc in cursor:
        for sc in doc.get("stock_codes", []) or []:
            if sc in stock_codes:
                grouped.setdefault(sc, []).append(doc)
    return grouped


# ─── Postgres: 체결 시뮬레이션용 다음 영업일 가격 ─────────────────────────────

def fetch_next_open(engine: Engine, day: date, stock_codes: list[str]
                    ) -> dict[str, dict]:
    """day 다음 영업일의 OHLC. PortfolioFn 의 시가 체결 시뮬레이션에 사용.

    공휴일 / 거래 정지로 다음 영업일이 없으면 그 종목은 dict 에서 빠짐 —
    AI 팀 PortfolioFn 이 미체결(None) 로 처리하면 됨.
    """
    if not stock_codes:
        return {}
    with engine.connect() as conn:
        # Postgres: DISTINCT ON 으로 종목별 다음 영업일 row 한 번에.
        rows = conn.execute(
            text("""
                SELECT DISTINCT ON (stock_code)
                    stock_code, date, open, high, low, close, volume
                FROM daily_ohlcv
                WHERE date > :d AND stock_code = ANY(:stocks)
                ORDER BY stock_code, date ASC
            """),
            {"d": _d(day), "stocks": stock_codes},
        ).mappings().all()

        # ── SQLite 롤백 시 복구 (MIN subquery + IN bindparam) ────────────────
        # stmt = text("""
        #     SELECT t.stock_code, t.date, t.open, t.high, t.low, t.close, t.volume
        #     FROM daily_ohlcv t
        #     INNER JOIN (
        #         SELECT stock_code, MIN(date) AS next_date
        #         FROM daily_ohlcv
        #         WHERE date > :d AND stock_code IN :stocks
        #         GROUP BY stock_code
        #     ) m
        #       ON t.stock_code = m.stock_code AND t.date = m.next_date
        # """).bindparams(bindparam("stocks", expanding=True))
        # rows = conn.execute(stmt, {"d": _d(day), "stocks": stock_codes}).mappings().all()

    # Postgres 는 DATE 컬럼이 date 객체로 옴 — _to_date 가 그대로 통과.
    # SQLite 롤백 시에도 _to_date 가 TEXT('YYYY-MM-DD') 를 date 로 변환.
    out = {}
    for r in rows:
        rec = dict(r)
        if "date" in rec and rec["date"] is not None:
            rec["date"] = _to_date(rec["date"])
        out[rec["stock_code"]] = rec
    return out
