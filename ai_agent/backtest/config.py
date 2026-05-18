"""백테스트 기본 파라미터.

런타임 옵션(시작·종료일·watchlist 등) 은 run_backtest.py CLI 인자로,
인프라 옵션(DSN·MONGO_URI) 은 환경변수로 주입한다.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from datetime import date

# ─── 데이터 소스 ──────────────────────────────────────────────────────────────
# 현재: Postgres (modu_db) — 마이그레이션 후 활성 상태.
# 로컬 SQLite 폴백은 아래 주석 블록으로 보존 (트러블슈팅·롤백용).
# Mongo: 공시 modu_mongo.disclosures, 뉴스 modu_mongo.news_articles (변경 없음).

# ── Postgres (현재 사용) ──────────────────────────────────────────────────────
POSTGRES_DSN_ENV = "DATABASE_URL"   # ai_agent.user_context.create_engine_from_env 와 동일

# ── SQLite (롤백용 — 활성화 시 위 Postgres 블록 주석 처리) ───────────────────
# repo 루트에서 backtest 위치: modu/ai_agent/backtest/config.py
# analysis_server SQLite: modu/analysis_server/data/stock_master.db
# from pathlib import Path as _Path
# DEFAULT_SQLITE_PATH = str(
#     (_Path(__file__).resolve().parents[2] / "analysis_server" / "data" / "stock_master.db")
# )
# SQLITE_PATH_ENV = "BACKTEST_SQLITE_PATH"   # override 가능 — 미설정 시 위 기본 경로 사용

MONGO_URI_ENV    = "MONGO_URI"
MONGO_DB         = "modu_mongo"
MONGO_DISCLOSURE_COLL = "disclosures"
MONGO_NEWS_COLL       = "news_articles"

# ─── 트리거 생성 윈도우 ───────────────────────────────────────────────────────
# analysis_server 와 정합:
#   event window  = 최근 2일 공시 (disclosure_collector.LOOKBACK_DAYS)
#   sentiment     = 당일 뉴스 집계
# 백테스트의 as_of(t) 시점에 t-(window-1) ~ t 의 데이터를 본다.

EVENT_LOOKBACK_DAYS    = 2
# 실 서비스 news_collector.update_sentiment_redis() 24h 윈도우 집계와 정합.
# as_of day 당일 기사 전체 평균 → Redis daily_score 재현.
SENTIMENT_LOOKBACK_DAYS = 1
MAX_DISCLOSURES_PER_STOCK = 10    # event payload 크기 cap (live 와 동일)

# ─── 시간 범위 기본값 ─────────────────────────────────────────────────────────
DEFAULT_START_DATE = date(2023, 1, 2)   # 23 첫 영업일
DEFAULT_END_DATE   = date(2024, 12, 31)


# ─── 환경변수 접근 ────────────────────────────────────────────────────────────

@dataclass(frozen=True)
class BacktestEnv:
    """런타임 환경 — 누락 시 즉시 에러 (백테스트 도중 의존 끊기는 비용이 큼)."""
    postgres_dsn: str
    mongo_uri: str
    # sqlite_path: str   # ── SQLite 롤백 시 복구


def load_env() -> BacktestEnv:
    dsn = os.getenv(POSTGRES_DSN_ENV)
    if not dsn:
        raise RuntimeError(
            f"필수 환경변수 누락: {POSTGRES_DSN_ENV} — repo 루트 .env 로드 확인",
        )

    # ── SQLite 롤백 시 복구 ─────────────────────────────────────────────────
    # sqlite_path = os.getenv(SQLITE_PATH_ENV, DEFAULT_SQLITE_PATH)
    # if not os.path.exists(sqlite_path):
    #     raise RuntimeError(
    #         f"SQLite 파일을 찾을 수 없습니다: {sqlite_path}\n"
    #         f"  - {SQLITE_PATH_ENV} 환경변수로 명시 가능\n"
    #         f"  - 기본 경로: {DEFAULT_SQLITE_PATH}",
    #     )

    mongo = os.getenv(MONGO_URI_ENV)
    if not mongo:
        raise RuntimeError(
            f"필수 환경변수 누락: {MONGO_URI_ENV} — repo 루트 .env 로드 확인",
        )

    return BacktestEnv(postgres_dsn=dsn, mongo_uri=mongo)
