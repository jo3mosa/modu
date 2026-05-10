import copy
import os
from typing import Any

from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine

# TODO: 고도화 시 investment-strategy.md 기반 DB 테이블로 교체
# 아래 상수들은 ERD에 테이블이 없어 하드코딩한 서비스 공통 정책이다.

_DOMESTIC_STOCK_RISK_POLICY: dict[str, Any] = {
    "normal_listed_stock": {
        "risk_grade": 2,
        "label": "고위험",
        "auto_buy_policy": "allowed_with_constraints",
    },
    "caution_or_warning_stock": {
        "risk_grade": 1,
        "label": "초고위험",
        "auto_buy_policy": "block",
    },
    "administrative_issue_stock": {
        "risk_grade": 1,
        "label": "초고위험",
        "auto_buy_policy": "block",
    },
    "trading_halt_stock": {
        "risk_grade": 1,
        "label": "초고위험",
        "auto_buy_policy": "block",
    },
}

_SYSTEM_TRADING_CONSTRAINTS: dict[str, Any] = {
    "max_number_of_positions": 3,
    "minimum_cash_ratio": {
        "value": 10,
        "unit": "%",
    },
    # 투자 성향 코드(risk_grade)별 단일 종목 최대 비중
    "investor_type_constraints": {
        "conservative": {"max_single_stock_ratio": 5},
        "stable":        {"max_single_stock_ratio": 10},
        "neutral":       {"max_single_stock_ratio": 15},
        "active":        {"max_single_stock_ratio": 20},
        "aggressive":    {"max_single_stock_ratio": 30},
    },
}

_ASSET_ALLOCATION: dict[str, Any] = {
    "max_single_stock_ratio": 20,
    "max_number_of_positions": 3,
    "minimum_cash_ratio": 10,
}

_MARKET_RULES: dict[str, Any] = {
    "restrict_new_entry_when": ["market_index_sharp_drop"],
    "market_drop_threshold": -3.0,
}

# TODO: 고도화 시 trading_rules.max_order_amount DB 컬럼으로 교체
_DEFAULT_MAX_ORDER_AMOUNT: int = 1_000_000


def load_user_context(user_id: int, engine: Engine) -> dict[str, Any]:
    """investment_profiles + trading_rules 테이블에서 사용자 투자 성향과 거래 규칙을 로드한다."""
    profile = _fetch_investment_profile(user_id, engine)
    rules = _fetch_trading_rules(user_id, engine)

    return {
        "investor_type": {
            "risk_grade": profile.get("risk_grade"),
            # risk_grade를 code로 사용 (active/conservative 등 동일 값)
            "code": profile.get("risk_grade"),
            "risk_score": profile.get("risk_score"),
            "investment_goal": profile.get("investment_goal"),
            "answers_snapshot": profile.get("answers_snapshot"),
        },
        "risk_rules": {
            "stop_loss_pct": rules.get("stop_loss_pct"),
            "take_profit_pct": rules.get("take_profit_pct"),
        },
        # TODO: 고도화 시 investment-strategy.md 기반 DB 테이블로 교체
        "domestic_stock_risk_policy": copy.deepcopy(_DOMESTIC_STOCK_RISK_POLICY),
    }


def load_policy_context(user_id: int, engine: Engine) -> dict[str, Any]:
    """auto_trade_settings 테이블 + 하드코딩 공통 정책으로 거래 정책을 구성한다."""
    settings = _fetch_auto_trade_settings(user_id, engine)
    auto_trade_status = settings.get("auto_trade_status")
    kill_switch_triggered_at = settings.get("kill_switch_triggered_at")

    return {
        "auto_trade_status": auto_trade_status,
        # TODO: auto_trade_status 허용 값을 백엔드 팀과 확인 필요 (ON/OFF/PAUSED 계열 가능성 있음)
        "allow_auto_trade": auto_trade_status == "active",
        "kill_switch": {
            "enabled": kill_switch_triggered_at is not None,
            "triggered": kill_switch_triggered_at is not None,
            "reason": settings.get("kill_switch_reason"),
        },
        # TODO: 고도화 시 investment-strategy.md 기반 DB 테이블로 교체
        "max_order_amount": _DEFAULT_MAX_ORDER_AMOUNT,
        "system_trading_constraints": copy.deepcopy(_SYSTEM_TRADING_CONSTRAINTS),
        "asset_allocation": copy.deepcopy(_ASSET_ALLOCATION),
        "market_rules": copy.deepcopy(_MARKET_RULES),
        "domestic_stock_risk_policy": copy.deepcopy(_DOMESTIC_STOCK_RISK_POLICY),
    }


def load_history_context(user_id: int, key_signals: list[str]) -> dict[str, Any]:
    """
    거래 복기 문서와 지표 가이드를 로드한다.

    MVP: stub 반환. 고도화 시 trade-history-wiki.md / llm-wiki.md 기반으로 구현.
    """
    _ = user_id, key_signals  # 고도화 시 사용
    return {
        "trade_history_wiki": "",
        "relevant_indicator_guides": {},
    }


def _fetch_investment_profile(user_id: int, engine: Engine) -> dict[str, Any]:
    query = text("""
        SELECT risk_score, risk_grade, investment_goal, answers_snapshot
        FROM investment_profiles
        WHERE user_id = :user_id
    """)
    with engine.connect() as conn:
        row = conn.execute(query, {"user_id": user_id}).mappings().first()
    return dict(row) if row else {}


def _fetch_trading_rules(user_id: int, engine: Engine) -> dict[str, Any]:
    query = text("""
        SELECT stop_loss_pct, take_profit_pct
        FROM trading_rules
        WHERE user_id = :user_id
    """)
    with engine.connect() as conn:
        row = conn.execute(query, {"user_id": user_id}).mappings().first()
    return dict(row) if row else {}


def _fetch_auto_trade_settings(user_id: int, engine: Engine) -> dict[str, Any]:
    query = text("""
        SELECT auto_trade_status, kill_switch_reason, kill_switch_triggered_at
        FROM auto_trade_settings
        WHERE user_id = :user_id
    """)
    with engine.connect() as conn:
        row = conn.execute(query, {"user_id": user_id}).mappings().first()
    return dict(row) if row else {}


def create_engine_from_env(database_url: str | None = None) -> Engine:
    """DATABASE_URL 환경변수(또는 인자)로 SQLAlchemy Engine을 생성한다."""
    url = database_url or os.getenv("DATABASE_URL")
    if not url:
        raise ValueError("DATABASE_URL is not set.")
    return create_engine(url)
