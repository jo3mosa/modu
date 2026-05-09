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
            "answers_snapshot": profile.get("answers_snapshot"),
        },
        "risk_rules": {
            "stop_loss_pct": rules.get("stop_loss_pct"),
            "take_profit_pct": rules.get("take_profit_pct"),
        },
    }


def load_policy_context(user_id: int, engine: Engine) -> dict[str, Any]:
    """auto_trade_settings 테이블에서 자동매매 정책을 로드한다."""
    settings = _fetch_auto_trade_settings(user_id, engine)

    return {
        "auto_trade_status": settings.get("auto_trade_status"),
        # kill_switch_triggered_at이 존재하면 kill switch가 발동된 상태
        "kill_switch_triggered": settings.get("kill_switch_triggered_at") is not None,
        "kill_switch_reason": settings.get("kill_switch_reason"),
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
