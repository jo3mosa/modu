"""DA backtest Trigger ↔ AI LangGraph 어댑터.

DA framework가 한 트리거마다 호출하는 `decision_fn(trigger, user_ctx, snapshot)`
시그니처를 LangGraph `run_pipeline`으로 채운다.

흐름:
    DA Trigger
      → UserTriggerEvent (트리거 + portfolio_snapshot + as_of 주입)
      → run_pipeline(event, mode)            # 그래프 본체 (실 LLM)
      → FinalDecision (Pydantic)
      → DA Decision (dataclass)

사용 예:
    from ai_agent.backtest.adapters.graph_decision import make_graph_decision_fn
    decision_fn = make_graph_decision_fn(mode="A")   # Bull/Bear 토론
    # 또는: make_graph_decision_fn(mode="B")          # 단일 에이전트
    # DA framework의 run() 에 그대로 넘김.
"""
from __future__ import annotations

import logging
from datetime import datetime
from typing import Any
from uuid import uuid4

from ..interfaces import Decision, Trigger

# DA framework는 ai_agent/backtest/에 있고 app/은 동일 ai_agent 패키지 안.
# 따라서 app.graph.runner 같은 절대 import가 가능 (ai_agent가 sys.path에 있을 때).
from app.graph.builder import GraphMode  # noqa: E402
from app.graph.runner import run_pipeline  # noqa: E402
from app.triggers.schemas import MarketTrigger, UserTriggerEvent  # noqa: E402

logger = logging.getLogger(__name__)


def make_graph_decision_fn(mode: GraphMode = "A", numeric_user_id: int = 1):
    """DA framework가 받는 decision_fn 형태로 LangGraph를 래핑한다.

    mode:
        A — Bull/Bear 토론 → Strategy Manager (MVP)
        B — context_loader → Strategy Manager 직결 (ablation)
    numeric_user_id:
        graph가 요구하는 int user_id. DA framework의 user_id(str) 와 분리.
        memory retrieval 시점 동일 user_id 누적용.
    """

    def decision_fn(trigger: Trigger, user_context: dict, portfolio_snapshot: Any) -> Decision:
        try:
            event = _to_user_trigger_event(
                trigger=trigger,
                portfolio_snapshot=portfolio_snapshot,
                user_context=user_context,
                user_id=numeric_user_id,
            )
            final_state = run_pipeline(event, mode=mode)
            return _to_da_decision(final_state)
        except Exception:
            logger.exception("graph_decision: 실패 → hold로 강등 (stock=%s)", trigger.stock_code)
            return Decision(action="hold", reasoning="graph_decision_fn 내부 예외")

    return decision_fn


def _to_user_trigger_event(
    *,
    trigger: Trigger,
    portfolio_snapshot: Any,
    user_context: dict,
    user_id: int,
) -> UserTriggerEvent:
    """DA Trigger를 우리 UserTriggerEvent로 변환.

    - as_of_date는 영업일 EOD 기준. 시뮬레이션 시각은 9:00 KST로 통일.
    - analysis_snapshot은 4종 signal payload(technical/fundamental/event/sentiment) 묶음.
    - portfolio_snapshot은 DA PortfolioFn.snapshot() 반환값. 실시간 경로 스키마와
      차이날 수 있으나 그래프 노드들은 to_json으로 그대로 전달하므로 dict면 OK.
    """
    as_of_dt = datetime.combine(trigger.as_of_date, datetime.min.time()).replace(hour=9)

    analysis_snapshot = {
        "stock_code": trigger.stock_code,
        "timestamp": as_of_dt.isoformat(),
        "signals": {
            "technical": trigger.technical,
            "fundamental": trigger.fundamental,
            "event": trigger.event,
            "sentiment": trigger.sentiment,
        },
    }

    portfolio_dict = portfolio_snapshot if isinstance(portfolio_snapshot, dict) else {}
    # 그래프 프롬프트가 current_price를 top-level로 기대 (user_trigger_matcher 동일 동작).
    if trigger.close_price is not None:
        portfolio_dict = {**portfolio_dict, "current_price": int(trigger.close_price)}

    return UserTriggerEvent(
        event_id=f"bt_{trigger.stock_code}_{trigger.as_of_date.isoformat()}_{uuid4().hex[:6]}",
        source_event_id=None,
        timestamp=as_of_dt,
        as_of=as_of_dt,
        user_id=user_id,
        stock_code=trigger.stock_code,
        trigger=MarketTrigger(
            rule_ids=list(trigger.rule_ids),
            trigger_reason=list(trigger.rule_reasons),
        ),
        analysis_snapshot=analysis_snapshot,
        portfolio_snapshot=portfolio_dict,
        user_context=dict(user_context or {}),
    )


def _to_da_decision(final_state: Any) -> Decision:
    """run_pipeline 반환에서 FinalDecision을 꺼내 DA Decision으로 변환.

    매핑:
      FinalDecision.action == "hold" → Decision(action="hold")
      FinalDecision.action == "trade" → Decision(action=side, ...)  # buy/sell

    target_price/stop_loss_price/order_amount/confidence/reasoning 그대로 전달.
    """
    fd = _get_field(final_state, "final_decision")
    if fd is None:
        return Decision(action="hold", reasoning="final_decision 없음")

    dump = fd.model_dump() if hasattr(fd, "model_dump") else dict(fd)
    action = dump.get("action")
    side = dump.get("side")

    if action == "hold" or side is None:
        return Decision(
            action="hold",
            confidence=dump.get("confidence"),
            reasoning=dump.get("reason_summary") or "",
        )

    return Decision(
        action=side,  # "buy" or "sell"
        order_amount=dump.get("order_amount"),
        target_price=dump.get("target_price"),
        stop_loss_price=dump.get("stop_loss_price"),
        confidence=dump.get("confidence"),
        reasoning=dump.get("reason_summary") or "",
        extras={
            "winning_side": _extract_winning_side(final_state),
            "bull_claim": _extract_bull_bear(final_state, "key_bull_points"),
            "bear_claim": _extract_bull_bear(final_state, "key_bear_points"),
        },
    )


def _extract_winning_side(final_state: Any) -> str | None:
    verdict = _get_field(final_state, "research_verdict")
    if verdict is None:
        return None
    dump = verdict.model_dump() if hasattr(verdict, "model_dump") else dict(verdict)
    return dump.get("winning_side")


def _extract_bull_bear(final_state: Any, key: str) -> str | None:
    verdict = _get_field(final_state, "research_verdict")
    if verdict is None:
        return None
    dump = verdict.model_dump() if hasattr(verdict, "model_dump") else dict(verdict)
    items = dump.get(key) or []
    return "\n".join(items) if items else None


def _get_field(state: Any, name: str) -> Any:
    if isinstance(state, dict):
        return state.get(name)
    return getattr(state, name, None)
