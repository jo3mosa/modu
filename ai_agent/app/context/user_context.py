import os
from typing import Any

from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine


def load_user_context(user_id: int, engine: Engine) -> dict[str, Any]:
    """
    investment_profiles + trading_rules 테이블에서 사용자 투자 성향과 거래 규칙을 로드한다.
    """
    profile = _fetch_investment_profile(user_id, engine)
    rules = _fetch_trading_rules(user_id, engine)

    return {
        "investor_type": {
            "risk_grade": profile.get("risk_grade"),
            "risk_score": profile.get("risk_score"),
            "investment_goal": profile.get("investment_goal"),
            "profile_summary": profile.get("profile_summary"),
        },
        "risk_rules": {
            "stop_loss_pct": rules.get("stop_loss_pct"),
            "take_profit_pct": rules.get("take_profit_pct"),
            "daily_loss_limit_pct": rules.get("daily_loss_limit_pct"),
            "max_order_amount": rules.get("max_order_amount"),
            "max_single_stock_pct": rules.get("max_single_stock_pct"),
            "max_holding_stocks": rules.get("max_holding_stocks"),
            "natural_language_rule": rules.get("natural_language_rule"),
            "parsed_rule_json": rules.get("parsed_rule_json"),
        },
    }


def load_policy_context(
    user_id: int,
    engine: Engine,
    user_context: dict[str, Any],
) -> dict[str, Any]:
    """
    auto_trade_settings + user_context의 risk_rules에서 거래 정책을 조합한다.

    trading_rules는 load_user_context에서 이미 조회했으므로 user_context에서 재사용한다.
    """
    settings = _fetch_auto_trade_settings(user_id, engine)
    risk_rules = user_context.get("risk_rules", {})

    return {
        "auto_trade_status": settings.get("auto_trade_status"),
        "kill_switch_triggered": settings.get("kill_switch_triggered_at") is not None,
        "kill_switch_reason": settings.get("kill_switch_reason"),
        "max_single_stock_pct": risk_rules.get("max_single_stock_pct"),
        "max_holding_stocks": risk_rules.get("max_holding_stocks"),
        "natural_language_rule": risk_rules.get("natural_language_rule"),
        "parsed_rule_json": risk_rules.get("parsed_rule_json"),
    }


def load_history_context(user_id: int, key_signals: list[str]) -> dict[str, Any]:
    """
    거래 복기 문서와 지표 가이드를 로드한다.

    MVP: stub 반환. trade_history_wiki 고도화 시 구현.
    """
    return {
        "trade_history_wiki": "",
        "relevant_indicator_guides": {},
    }


def _fetch_investment_profile(user_id: int, engine: Engine) -> dict[str, Any]:
    query = text("""
        SELECT risk_score, risk_grade, profile_summary, investment_goal
        FROM investment_profiles
        WHERE user_id = :user_id
    """)
    with engine.connect() as conn:
        row = conn.execute(query, {"user_id": user_id}).mappings().first()
    return dict(row) if row else {}


def _fetch_trading_rules(user_id: int, engine: Engine) -> dict[str, Any]:
    query = text("""
        SELECT stop_loss_pct, take_profit_pct, daily_loss_limit_pct,
               max_order_amount, max_single_stock_pct, max_holding_stocks,
               natural_language_rule, parsed_rule_json
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
    url = database_url or os.getenv("DATABASE_URL")
    if not url:
        raise ValueError("DATABASE_URL is not set.")
    return create_engine(url)
