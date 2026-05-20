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
    decision_fn = make_graph_decision_fn(mode="debate_1")   # Bull/Bear 1라운드 토론 (MVP)
    # 또는: make_graph_decision_fn(mode="debate_0")          # 토론 없음 (ablation)
    # 또는: make_graph_decision_fn(mode="debate_2")          # Bull/Bear 2라운드 토론
    # DA framework의 run() 에 그대로 넘김.
"""
from __future__ import annotations

import logging
import sys
from datetime import datetime
from pathlib import Path
from typing import Any
from uuid import uuid4

from sqlalchemy.engine import Engine

from ..interfaces import Decision, Trigger

# repo 루트에서 `python -m ai_agent.backtest.run_ai_backtest`로 실행 시
# cwd는 repo 루트이고 sys.path에 `ai_agent/`가 없어 `app.*` import 실패.
# 명시적으로 ai_agent/ 디렉터리를 추가해 production import 경로(app.*)를 살린다.
_AI_AGENT_ROOT = Path(__file__).resolve().parents[2]
if str(_AI_AGENT_ROOT) not in sys.path:
    sys.path.insert(0, str(_AI_AGENT_ROOT))

from app.graph.builder import GraphMode  # noqa: E402
from app.graph.runner import run_pipeline  # noqa: E402
from app.memory.db_store import DBMemoryStore  # noqa: E402
from app.memory.interfaces import DecisionLog  # noqa: E402
from app.triggers.schemas import MarketTrigger, UserTriggerEvent  # noqa: E402

# trigger별 LLM 토큰/비용 캡처. langchain_community 미설치 환경에서도 graceful.
try:
    from langchain_community.callbacks.manager import get_openai_callback  # noqa: E402
except ImportError:  # pragma: no cover
    get_openai_callback = None  # type: ignore[assignment]

logger = logging.getLogger(__name__)


def make_graph_decision_fn(
    mode: GraphMode = "debate_1",
    numeric_user_id: int = 1,
    engine: Engine | None = None,
):
    """DA framework가 받는 decision_fn 형태로 LangGraph를 래핑한다.

    mode:
        A — Bull/Bear 토론 → Strategy Manager (MVP)
        B — context_loader → Strategy Manager 직결 (ablation)
    numeric_user_id:
        graph가 요구하는 int user_id. DA framework의 user_id(str) 와 분리.
        memory retrieval 시점 동일 user_id 누적용.
    engine:
        주입 시 결정을 ai_judgments에 INSERT하고 ai_judgment_id를
        Decision.extras에 부착. None이면 DB write skip — reflection loop 안 닫힘.
        production은 Kafka → backend가 INSERT하므로 ai_agent 단독 실행 시 필요.
    """
    memory_store = DBMemoryStore(engine) if engine is not None else None

    def decision_fn(trigger: Trigger, user_context: dict, portfolio_snapshot: Any) -> Decision:
        try:
            event = _to_user_trigger_event(
                trigger=trigger,
                portfolio_snapshot=portfolio_snapshot,
                user_id=numeric_user_id,
            )
            # LLM 토큰/비용 캡처 — get_openai_callback이 run_pipeline 안의 모든
            # LLM 호출(bull/bear/strategy_manager/decision_manager)을 자동 누적.
            # langchain_community 미설치 시 None — fallback 경로로 그래프만 실행.
            token_usage: dict[str, Any] = {}
            if get_openai_callback is not None:
                with get_openai_callback() as cb:
                    final_state = run_pipeline(event, mode=mode)
                token_usage = {
                    "prompt_tokens": int(cb.prompt_tokens),
                    "completion_tokens": int(cb.completion_tokens),
                    "total_tokens": int(cb.total_tokens),
                    "estimated_cost_usd": float(cb.total_cost),
                }
            else:
                final_state = run_pipeline(event, mode=mode)

            da_decision = _to_da_decision(final_state)
            if token_usage:
                new_extras = dict(da_decision.extras or {})
                new_extras.update(token_usage)
                da_decision = _replace_extras(da_decision, new_extras)

            if memory_store is not None:
                ai_judgment_id = _persist_decision(
                    memory_store=memory_store,
                    event=event,
                    final_state=final_state,
                    user_context=user_context,
                )
                if ai_judgment_id is not None:
                    new_extras = dict(da_decision.extras or {})
                    new_extras["ai_judgment_id"] = ai_judgment_id
                    da_decision = _replace_extras(da_decision, new_extras)
            return da_decision
        except Exception:
            logger.exception("graph_decision: 실패 → hold로 강등 (stock=%s)", trigger.stock_code)
            return Decision(action="hold", reasoning="graph_decision_fn 내부 예외")

    return decision_fn


def _replace_extras(decision: Decision, extras: dict) -> Decision:
    """frozen dataclass라 replace 패턴 — 기존 필드 보존 + extras만 갱신."""
    return Decision(
        action=decision.action,
        order_amount=decision.order_amount,
        target_price=decision.target_price,
        stop_loss_price=decision.stop_loss_price,
        confidence=decision.confidence,
        reasoning=decision.reasoning,
        extras=extras,
    )


def _persist_decision(
    *,
    memory_store: DBMemoryStore,
    event: UserTriggerEvent,
    final_state: Any,
    user_context: dict,
) -> int | None:
    """final_state를 DecisionLog로 변환해 ai_judgments에 INSERT.

    user_context는 UserTriggerEvent schema에 없으므로 별도 인자로 받는다
    (schema 주석: "context_loader가 DB에서 로드"). risk_grade 매핑에만 사용.
    실패 시 logger.exception 후 None — 회고 매핑 못해도 backtest 본 흐름은 진행.
    """
    try:
        log = _build_decision_log(event, final_state, user_context)
        if log is None:
            return None
        return memory_store.store_decision(log, judged_at=event.as_of)
    except Exception:
        logger.exception("graph_decision: store_decision 실패 (stock=%s)", event.stock_code)
        return None


def _build_decision_log(
    event: UserTriggerEvent,
    final_state: Any,
    user_context: dict,
) -> DecisionLog | None:
    """final_state + event → DecisionLog (TypedDict).

    FinalDecision 없으면 None 반환 → INSERT skip.
    DB의 decision enum은 BUY/SELL/HOLD 대문자.
    """
    final_decision = _get_field(final_state, "final_decision")
    if final_decision is None:
        return None
    fd_dump = final_decision.model_dump() if hasattr(final_decision, "model_dump") else dict(final_decision)

    action = fd_dump.get("action")
    side = fd_dump.get("side")
    if action == "hold" or side not in ("buy", "sell"):
        decision_enum = "HOLD"
        order_amount = 0
    else:
        decision_enum = side.upper()
        order_amount = int(fd_dump.get("order_amount") or 0)

    verdict = _get_field(final_state, "research_verdict")
    verdict_dump = (
        verdict.model_dump() if hasattr(verdict, "model_dump")
        else dict(verdict) if verdict else {}
    )

    winning_raw = verdict_dump.get("winning_side") if verdict_dump else None
    winning_side = winning_raw.upper() if winning_raw else None

    # ai_agent/app/context/memory_context.py extract_key_signals 재사용
    from app.context.memory_context import extract_key_signals
    key_signals = extract_key_signals(event.analysis_snapshot or {})

    log: DecisionLog = {
        "user_id": int(event.user_id),
        "stock_code": event.stock_code,
        "sector": None,   # analysis_snapshot에 sector 없음 — 추후 stock_master JOIN 추가 여지
        "risk_grade": (user_context or {}).get("risk_profile"),
        "decision": decision_enum,
        "order_amount": order_amount,
        "target_price": _to_int_or_none(fd_dump.get("target_price")),
        "stop_loss_price": _to_int_or_none(fd_dump.get("stop_loss_price")),
        "judgment_reason": fd_dump.get("reason_summary") or "",
        "key_signals": key_signals,
        "confidence_score": int(round((fd_dump.get("confidence") or 0.0) * 100)),
        "bull_claim": _join_lines(verdict_dump.get("key_bull_points")),
        "bear_claim": _join_lines(verdict_dump.get("key_bear_points")),
        "winning_side": winning_side,
        "expected_scenario": None,
        "indicators_snapshot": event.analysis_snapshot or {},
    }
    return log


def _to_int_or_none(value: Any) -> int | None:
    if value is None:
        return None
    try:
        return int(round(float(value)))
    except (TypeError, ValueError):
        return None


def _join_lines(items: Any) -> str | None:
    if not items:
        return None
    if isinstance(items, list):
        return "\n".join(str(x) for x in items)
    return str(items)


def _to_user_trigger_event(
    *,
    trigger: Trigger,
    portfolio_snapshot: Any,
    user_id: int,
) -> UserTriggerEvent:
    """DA Trigger를 우리 UserTriggerEvent로 변환.

    user_context는 schema에 없다 — context_loader가 그래프 실행 중 DB에서
    로드한다. backtest용 user_context는 _persist_decision에 별도 전달.

    - as_of_date는 영업일 EOD 기준. 시뮬레이션 시각은 9:00 KST로 통일.
    - analysis_snapshot은 4종 signal payload(technical/fundamental/event/sentiment) 묶음.
    - portfolio_snapshot은 DA PortfolioFn.snapshot() 반환값. 실시간 경로 스키마와
      차이날 수 있으나 그래프 노드들은 to_json으로 그대로 전달하므로 dict면 OK.
    """
    as_of_dt = datetime.combine(trigger.as_of_date, datetime.min.time()).replace(hour=9)

    analysis_snapshot = {
        "technical": trigger.technical,
        "fundamental": trigger.fundamental,
        "event": trigger.event,
        "sentiment": trigger.sentiment,
        **({"news_summary": trigger.news_summary} if trigger.news_summary else {}),
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
            extras={
                "winning_side": _extract_winning_side(final_state),
                "bull_claim": _extract_bull_bear(final_state, "key_bull_points"),
                "bear_claim": _extract_bull_bear(final_state, "key_bear_points"),
            },
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
