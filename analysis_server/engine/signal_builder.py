"""signal_builder

Redis(`technical`, `event`, `sentiment`) + DB(`daily_fundamentals` 최신 1행)
→ Analysis Signal 조합. 1분 주기 엔진 사이클의 첫 단계.

`Signal` 객체는 engine 안에서만 살아 있는 in-memory 컨테이너 (Redis/Kafka 안 거침).
모양은 architecture spec 의 analysis_signals 와 1:1 일치:
    {"stock_code", "timestamp", "signals": {
        "technical":   Redis technical:{stock} (None 가능),
        "fundamental": DB daily_fundamentals 최신 row (None 가능),
        "event":       Redis event:{stock} (None 가능),
        "sentiment":   Redis sentiment:{stock} (None 가능),
    }}

raw 재무 수치를 분류 카테고리(valuation/profitability/growth/stability)로 매핑하는
classifier 들을 보유한다 — fundamental_loader / historical_fundamental_loader 가 import 해
DB 의 status 컬럼을 채울 때 쓴다. signal_builder.build() 자체는 이미 status 가
채워진 DB row 를 그대로 읽기만 한다 (classifier 재호출 안 함).
"""

import os
import sqlite3
from dataclasses import dataclass, field
from datetime import datetime
from typing import Optional
from zoneinfo import ZoneInfo

from clients.redis_client import get_json

KST = ZoneInfo("Asia/Seoul")

_MODULE_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_DB = os.path.join(_MODULE_DIR, "..", "data", "stock_master.db")


def classify_valuation(per, pbr):
    if per is None or pbr is None:
        return "unknown"
    if per < 10 and pbr < 1.0:
        return "undervalued"
    if per > 25 or pbr > 3.0:
        return "overvalued"
    return "fair"


def classify_profitability(roe):
    if roe is None:
        return "unknown"
    if roe >= 15:
        return "high_margin"
    if roe >= 8:
        return "normal"
    return "low_margin"


def classify_growth(curr, prev):
    if not curr or not prev:
        return "unknown"
    rev_curr, rev_prev = curr.get("매출액"), prev.get("매출액")
    op_curr, op_prev = curr.get("영업이익"), prev.get("영업이익")
    if not rev_curr or not rev_prev:
        return "unknown"

    rev_growth = (rev_curr - rev_prev) / abs(rev_prev) * 100
    op_growth = ((op_curr - op_prev) / abs(op_prev) * 100) if (op_curr and op_prev) else 0

    if rev_growth >= 20 and op_growth >= 20:
        return "high_growth"
    if rev_growth >= 5:
        return "steady_growth"
    if rev_growth < -5:
        return "declining"
    return "stagnant"


def classify_stability(accounts):
    # 필수 계정 결측 시 unknown — 결측을 stable/moderate로 오분류 방지
    required = ("자본총계", "부채총계", "유동자산", "유동부채")
    if any(accounts.get(k) is None for k in required):
        return "unknown"

    equity = accounts["자본총계"]
    debt = accounts["부채총계"]
    current_assets = accounts["유동자산"]
    current_liab = accounts["유동부채"]

    # 자본잠식
    if equity <= 0:
        return "risky"

    debt_ratio = debt / equity * 100
    current_ratio = (current_assets / current_liab * 100) if current_liab > 0 else None

    if debt_ratio > 200:
        return "risky"
    if current_ratio is not None and current_ratio < 100:
        return "risky"
    if debt_ratio < 100 and (current_ratio is None or current_ratio > 150):
        return "stable"
    return "moderate"


@dataclass
class Signal:
    """Engine 내부 통합 데이터 컨테이너. 직렬화 X (Kafka 로 보내는 payload 는 별도)."""
    stock_code: str
    timestamp: datetime
    # signals 의 4 source 각각:
    #   technical  : {"snapshot", "trend", "momentum", "volatility", "volume"} | None
    #   fundamental: {"valuation", "profitability", "growth", "stability"} | None
    #   event      : {"has_urgent_issue", "recent_disclosures"} | None
    #   sentiment  : {"daily_score", "confidence_level", "pos_prob"/"neu_prob"/"neg_prob"} | None
    signals: dict = field(default_factory=dict)


def _load_fundamental_from_db(stock_code: str, db_path: str = DEFAULT_DB) -> Optional[dict]:
    """daily_fundamentals 의 가장 최근 1행 → analysis_signals.fundamental 형태.

    fundamental_loader 가 매일 적재해두므로 가장 최근 = 오늘 (또는 직전 영업일).
    행이 없으면 None — signal_builder 가 그대로 None 으로 채워 detection_engine 이
    safe-access 로 처리.
    """
    with sqlite3.connect(db_path) as conn:
        conn.row_factory = sqlite3.Row
        row = conn.execute(
            "SELECT * FROM daily_fundamentals WHERE stock_code = ? "
            "ORDER BY date DESC LIMIT 1",
            (stock_code,),
        ).fetchone()
    if row is None:
        return None
    return {
        "valuation": {
            "per":    row["per"],
            "pbr":    row["pbr"],
            "status": row["valuation_status"],
        },
        "profitability": {
            "roe":    row["roe"],
            "status": row["profitability_status"],
        },
        "growth": {
            "revenue_growth":   row["revenue_growth"],
            "operating_growth": row["operating_growth"],
            "status":           row["growth_status"],
        },
        "stability": {
            "debt_ratio":    row["debt_ratio"],
            "current_ratio": row["current_ratio"],
            "status":        row["stability_status"],
        },
    }


def build(stock_code: str, db_path: str = DEFAULT_DB) -> Signal:
    """4 소스 통합 Signal 생성. 모든 소스 None 가능 — caller 가 안전 처리.

    이 함수는 read-only (Redis GET × 3 + DB SELECT × 1). 사이드 이펙트 없음.
    """
    return Signal(
        stock_code=stock_code,
        timestamp=datetime.now(KST),
        signals={
            "technical":   get_json(f"technical:{stock_code}"),
            "fundamental": _load_fundamental_from_db(stock_code, db_path),
            "event":       get_json(f"event:{stock_code}"),
            "sentiment":   get_json(f"sentiment:{stock_code}"),
        },
    )
