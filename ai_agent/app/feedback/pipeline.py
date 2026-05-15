import json
import logging
from typing import Any

from sqlalchemy import text
from sqlalchemy.engine import Engine

from app.agents.feedback.post_mortem_agent import post_mortem_agent
from app.feedback.schemas import TradeSettledEvent
from app.memory.db_store import DBMemoryStore
from app.memory.interfaces import PostMortemRecord

logger = logging.getLogger(__name__)


def run_post_mortem(
    event: TradeSettledEvent,
    engine: Engine,
    memory_store: DBMemoryStore | None = None,
) -> int | None:
    """
    TradeSettledEvent를 받아 회고를 생성하고 DB에 저장한다.

    흐름:
    1. ai_judgment_id로 과거 결정 컨텍스트(judgment_reason + bull/bear claim + winning_side) 조회
    2. post_mortem_agent에 결정 + PnL 전달 → PostMortemReflection 받음
    3. PostMortemRecord로 변환 → store_postmortem

    반환:
    - 성공: 생성된 post_mortem_reports.id
    - 실패 (결정 없음 / LLM 실패 / DB 실패): None. 회고는 silent skip이 안전.
    """

    store = memory_store or DBMemoryStore(engine)

    decision_context = _load_decision_context(event.ai_judgment_id, engine)
    if decision_context is None:
        logger.warning(
            "post_mortem: ai_judgment_id=%s 결정 레코드를 찾을 수 없어 skip",
            event.ai_judgment_id,
        )
        return None

    reflection = post_mortem_agent(
        decision_content=decision_context["decision_content"],
        raw_return=event.raw_return,
        alpha_return=event.alpha_return,
        holding_days=event.holding_days,
        risk_level=decision_context.get("risk_grade"),
        key_signals=decision_context.get("key_signals"),
    )

    if reflection is None:
        # post_mortem_agent 내부에서 이미 로깅함.
        return None

    record: PostMortemRecord = {
        "ai_judgment_id": event.ai_judgment_id,
        "trade_pnl_record_id": event.trade_pnl_record_id,
        "entry_timing_assessment": reflection.entry_timing_assessment,
        "exit_rule_assessment": reflection.exit_rule_assessment,
        "risk_prediction_accuracy": reflection.risk_prediction_accuracy,
        "missed_signals": reflection.missed_signals,
        "lessons": reflection.lessons,
        "summary": reflection.summary,
    }

    try:
        record_id = store.store_postmortem(record)
    except Exception:
        logger.exception(
            "post_mortem: DB 저장 실패 ai_judgment_id=%s",
            event.ai_judgment_id,
        )
        return None

    logger.info(
        "post_mortem: 저장 성공 ai_judgment_id=%s, record_id=%s, raw_return=%+.2f%%, alpha=%+.2f%%",
        event.ai_judgment_id,
        record_id,
        event.raw_return * 100,
        event.alpha_return * 100,
    )
    return record_id


def _load_decision_context(ai_judgment_id: int, engine: Engine) -> dict[str, Any] | None:
    """
    ai_judgments에서 회고에 필요한 결정 정보를 조회한다.

    judgment_reason + bull_claim + bear_claim + winning_side를 하나의 텍스트로 합쳐
    post_mortem_agent의 decision_content로 전달한다.
    """
    query = text("""
        SELECT
            judgment_reason,
            bull_claim,
            bear_claim,
            winning_side,
            risk_grade,
            key_signals
        FROM ai_judgments
        WHERE id = :ai_judgment_id
    """)

    with engine.connect() as conn:
        row = conn.execute(query, {"ai_judgment_id": ai_judgment_id}).mappings().first()

    if row is None:
        return None

    parts: list[str] = []
    judgment_reason = row.get("judgment_reason")
    if judgment_reason:
        parts.append(f"[판단 사유]\n{judgment_reason}")
    if row.get("bull_claim"):
        parts.append(f"[Bull 주장]\n{row['bull_claim']}")
    if row.get("bear_claim"):
        parts.append(f"[Bear 주장]\n{row['bear_claim']}")
    if row.get("winning_side"):
        parts.append(f"[우세 관점]\n{row['winning_side']}")

    raw_key_signals = row.get("key_signals")
    key_signals: list[str] = (
        raw_key_signals if isinstance(raw_key_signals, list)
        else json.loads(raw_key_signals) if raw_key_signals
        else []
    )

    return {
        "decision_content": "\n\n".join(parts) if parts else "(판단 사유 없음)",
        "risk_grade": row.get("risk_grade"),
        "key_signals": key_signals,
    }
