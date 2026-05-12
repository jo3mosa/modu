"""Backtest replay runner.

거래일 순회 → signal 생성 → run_pipeline → portfolio 반영 → JSONL 기록.

실시간 파이프와 그래프 본체(run_pipeline)를 공유한다. 차이점은:
- 트리거: Kafka 대신 SignalSource가 직접 MarketTriggerEvent를 생성
- 시간: as_of를 명시적으로 주입해 retrieval/memory_log가 시뮬 시각 기준으로 동작
- 포트폴리오: KIS API 대신 PortfolioSim이 가상 추적
- 출력: Kafka publish 대신 JSONL append
"""
import json
import logging
from dataclasses import dataclass, field
from datetime import date, datetime
from pathlib import Path
from typing import Any, Protocol

from app.backtest.clock import SimulatedClock
from app.backtest.portfolio_sim import PortfolioSim
from app.backtest.signal_replay import MockSignalSource, SignalSource
from app.graph.runner import run_pipeline
from app.memory.interfaces import MemoryStore
from app.triggers.schemas import MarketTriggerEvent, UserTriggerEvent

logger = logging.getLogger(__name__)


class PriceFetcher(Protocol):
    """결정 시점 체결가를 제공한다. 실제 OHLCV DB 조회는 다음 PR."""

    def close_price(self, stock_code: str, target_date: date) -> float: ...


class StubPriceFetcher:
    """고정 가격을 돌려주는 stub. 실제 DB 연동 전 smoke test용."""

    def __init__(self, default_price: float = 70_000.0) -> None:
        self._price = default_price

    def close_price(self, stock_code: str, target_date: date) -> float:
        return self._price


@dataclass
class BacktestConfig:
    user_id: int
    stock_codes: list[str]
    start: date
    end: date
    output_path: Path
    initial_cash: int = 10_000_000
    user_context: dict[str, Any] = field(default_factory=dict)


def run_backtest(
    config: BacktestConfig,
    signal_source: SignalSource | None = None,
    memory_store: MemoryStore | None = None,
    price_fetcher: PriceFetcher | None = None,
) -> None:
    """backtest 메인 루프.

    signal_source: 미주입 시 MockSignalSource 사용
    memory_store: 주입 시 결정을 시뮬 시각으로 저장해 retrieval 누적 (콜드 스타트 → 자연 누적)
    price_fetcher: 미주입 시 StubPriceFetcher 사용
    """
    signal_source = signal_source or MockSignalSource()
    price_fetcher = price_fetcher or StubPriceFetcher()
    clock = SimulatedClock(config.start, config.end)
    portfolio = PortfolioSim(config.initial_cash)

    config.output_path.parent.mkdir(parents=True, exist_ok=True)
    with config.output_path.open("a", encoding="utf-8") as f:
        while not clock.is_done():
            events = signal_source.signals_for(clock.current_date, config.stock_codes)
            for event in events:
                _process_event(
                    event=event,
                    clock=clock,
                    config=config,
                    portfolio=portfolio,
                    memory_store=memory_store,
                    price_fetcher=price_fetcher,
                    output_file=f,
                )
            clock.tick()


def _process_event(
    *,
    event: MarketTriggerEvent,
    clock: SimulatedClock,
    config: BacktestConfig,
    portfolio: PortfolioSim,
    memory_store: MemoryStore | None,
    price_fetcher: PriceFetcher,
    output_file: Any,
) -> None:
    as_of = clock.current_as_of
    user_event = _to_user_event(event, config, portfolio, as_of)

    try:
        final_state = run_pipeline(user_event)
    except Exception as exc:
        logger.exception("run_pipeline 실패 %s %s", clock.current_date, event.stock_code)
        _write_record(output_file, {
            "date": clock.current_date.isoformat(),
            "stock_code": event.stock_code,
            "rule_ids": event.trigger.rule_ids,
            "flow_status": "failed",
            "error": str(exc),
        })
        return

    decision = _extract_decision(final_state)
    price = price_fetcher.close_price(event.stock_code, clock.current_date)
    exec_result = portfolio.apply_decision(
        stock_code=event.stock_code,
        side=decision.get("side"),
        order_amount=decision.get("order_amount"),
        execution_price=price,
    )

    if memory_store is not None:
        _persist_decision(memory_store, final_state, event, config.user_id, as_of)

    _write_record(output_file, {
        "date": clock.current_date.isoformat(),
        "as_of": as_of.isoformat(),
        "user_id": config.user_id,
        "stock_code": event.stock_code,
        "rule_ids": event.trigger.rule_ids,
        "trigger_reason": event.trigger.trigger_reason,
        "flow_status": _get_state_field(final_state, "flow_status"),
        "action": decision.get("action"),
        "side": decision.get("side"),
        "order_amount": decision.get("order_amount"),
        "target_price": decision.get("target_price"),
        "stop_loss_price": decision.get("stop_loss_price"),
        "confidence": decision.get("confidence"),
        "execution_price": price,
        "execution_result": exec_result,
        "portfolio_after": portfolio.snapshot(),
    })


def _to_user_event(
    event: MarketTriggerEvent,
    config: BacktestConfig,
    portfolio: PortfolioSim,
    as_of: datetime,
) -> UserTriggerEvent:
    return UserTriggerEvent(
        source_event_id=event.event_id,
        timestamp=event.timestamp,
        as_of=as_of,
        user_id=config.user_id,
        stock_code=event.stock_code,
        trigger=event.trigger,
        analysis_snapshot=event.analysis_snapshot,
        portfolio_snapshot=portfolio.snapshot(),
        user_context=config.user_context,
    )


def _extract_decision(final_state: Any) -> dict[str, Any]:
    """run_pipeline 반환은 dict(LangGraph)이지만 FinalDecision은 pydantic. 둘 다 지원."""
    fd = _get_state_field(final_state, "final_decision")
    if fd is None:
        return {}
    dump = fd.model_dump() if hasattr(fd, "model_dump") else dict(fd)
    return {
        "action": dump.get("action"),
        "side": dump.get("side"),
        "order_amount": dump.get("order_amount"),
        "target_price": dump.get("target_price"),
        "stop_loss_price": dump.get("stop_loss_price"),
        "confidence": dump.get("confidence"),
    }


def _persist_decision(
    memory_store: MemoryStore,
    final_state: Any,
    event: MarketTriggerEvent,
    user_id: int,
    as_of: datetime,
) -> None:
    """그래프 결정을 시뮬 시각으로 ai_judgments에 저장. 실패해도 backtest는 계속."""
    fd = _get_state_field(final_state, "final_decision")
    verdict = _get_state_field(final_state, "research_verdict")
    if fd is None:
        return
    fd_dump = fd.model_dump() if hasattr(fd, "model_dump") else dict(fd)
    verdict_dump = (
        verdict.model_dump() if hasattr(verdict, "model_dump")
        else dict(verdict) if verdict else {}
    )

    decision_str = _to_decision_enum(fd_dump.get("action"), fd_dump.get("side"))
    log = {
        "user_id": user_id,
        "stock_code": event.stock_code,
        "sector": None,
        "risk_grade": None,
        "decision": decision_str,
        "order_amount": fd_dump.get("order_amount") or 0,
        "target_price": int(fd_dump["target_price"]) if fd_dump.get("target_price") else None,
        "stop_loss_price": int(fd_dump["stop_loss_price"]) if fd_dump.get("stop_loss_price") else None,
        "judgment_reason": fd_dump.get("reason_summary", ""),
        "key_signals": event.trigger.rule_ids,
        "confidence_score": int((fd_dump.get("confidence") or 0) * 100),
        "bull_claim": "\n".join(verdict_dump.get("key_bull_points", []) or []) or None,
        "bear_claim": "\n".join(verdict_dump.get("key_bear_points", []) or []) or None,
        "winning_side": (verdict_dump.get("winning_side") or "").upper() or None,
        "expected_scenario": None,
        "indicators_snapshot": event.analysis_snapshot.get("signals", {}),
    }
    try:
        memory_store.store_decision(log, judged_at=as_of)
    except Exception:
        logger.exception("store_decision 실패 (backtest 계속 진행)")


def _to_decision_enum(action: str | None, side: str | None) -> str:
    if action == "hold" or side is None:
        return "HOLD"
    if side == "buy":
        return "BUY"
    if side == "sell":
        return "SELL"
    return "HOLD"


def _get_state_field(state: Any, field_name: str) -> Any:
    if isinstance(state, dict):
        return state.get(field_name)
    return getattr(state, field_name, None)


def _write_record(f: Any, record: dict[str, Any]) -> None:
    f.write(json.dumps(record, ensure_ascii=False, default=str) + "\n")
