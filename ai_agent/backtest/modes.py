"""Backtest mode registry — 새 ablation을 한 곳에서 등록.

새 mode를 추가하려면 이 파일의 `MODE_REGISTRY`에 항목 하나만 추가하면 된다.
런타임 모든 분기(CLI choices / decision_fn 빌드 / DB 사용 여부 / persist 여부 /
streamlit selectbox)는 registry를 단일 source로 동작한다.

ModeSpec 필드:
    factory     — (backtest_user_id, engine) → DecisionFn
                  random/mock은 인자 사용 안 함. A/B는 LangGraph 빌더에 mode 문자열 전달.
    description — UI/도움말에 노출되는 한 줄 설명
    uses_llm    — LLM 호출 여부 (cost 안내/key 검증)
    uses_db     — ai_judgments INSERT 여부 (engine 필요 + ensure_user 필요)
                  False면 reflection loop 안 닫힘 — baseline 비교용 mode.

신규 mode 등록 예:
    "C": ModeSpec(
        factory=lambda uid, eng: _build_graph_factory("C")(uid, eng),
        description="LangGraph mode C — context_loader → critic → decision",
        uses_llm=True,
        uses_db=True,
    ),
새 LangGraph 변형이면 app/graph/builder.py의 build_investment_graph에도 분기 추가 필요.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable, Optional

from .interfaces import DecisionFn


@dataclass(frozen=True)
class ModeSpec:
    """단일 mode의 메타데이터 + 의사결정 함수 factory.

    signal_factory:
        None 이면 signal_generator.detect_all() 사용 (기본 지표 룰 트리거).
        설정 시 해당 factory가 반환하는 signal_fn 으로 트리거 생성을 대체.
        시그니처: (backtest_user_id, engine) → signal_fn
        signal_fn 시그니처: detect_all() 과 동일.
    """
    factory: Callable[[int, Any], DecisionFn]
    description: str
    uses_llm: bool
    uses_db: bool
    signal_factory: Optional[Callable[[int, Any], Any]] = field(default=None)


def _build_random_fn(backtest_user_id: int, engine: Any) -> DecisionFn:
    """LLM/DB 없음. seed=42 고정 — 같은 trigger에 같은 결정 (재현성)."""
    from .adapters.random_decision import make_random_decision_fn
    return make_random_decision_fn(seed=42)


def _build_mock_fn(backtest_user_id: int, engine: Any) -> DecisionFn:
    """DA simple_rule_decision — rule_id 패턴 매칭만. LLM/DB 없음."""
    from .examples.mock_decision import simple_rule_decision
    return simple_rule_decision


def _build_always_trigger_factory() -> Callable[[int, Any], Any]:
    """TradingAgents 방식 — 전 종목 매일 무조건 LangGraph 진입."""
    def factory(backtest_user_id: int, engine: Any) -> Any:
        from .adapters.always_trigger import make_always_trigger_signal_fn
        return make_always_trigger_signal_fn()
    return factory


def _build_graph_factory(mode_name: str) -> Callable[[int, Any], DecisionFn]:
    """LangGraph mode (A/B/...) 의 factory 생성.

    Args:
        mode_name: build_investment_graph가 받는 mode 문자열.
    """
    def factory(backtest_user_id: int, engine: Any) -> DecisionFn:
        from .adapters.graph_decision import make_graph_decision_fn
        return make_graph_decision_fn(
            mode=mode_name,
            numeric_user_id=backtest_user_id,
            engine=engine,
        )
    return factory


MODE_REGISTRY: dict[str, ModeSpec] = {
    "random": ModeSpec(
        factory=_build_random_fn,
        description="LLM 미호출 baseline (랜덤 결정)",
        uses_llm=False,
        uses_db=False,
    ),
    "mock": ModeSpec(
        factory=_build_mock_fn,
        description="DA simple_rule_decision (룰 패턴 stub)",
        uses_llm=False,
        uses_db=False,
    ),
    "rule_trigger": ModeSpec(
        factory=_build_graph_factory("A"),
        description="[우리 방식] 지표 룰 트리거 → LangGraph Bull/Bear 토론 → 결정",
        uses_llm=True,
        uses_db=True,
    ),
    "daily_scan": ModeSpec(
        factory=_build_graph_factory("A"),
        description="[TradingAgents 방식] 전 종목 매일 LangGraph 진입, HOLD=사실상 패스",
        uses_llm=True,
        uses_db=True,
        signal_factory=_build_always_trigger_factory(),
    ),
    "single_agent": ModeSpec(
        factory=_build_graph_factory("B"),
        description="[ablation] Strategy → Decision 직결 (Bull/Bear 토론 없음)",
        uses_llm=True,
        uses_db=True,
    ),
}


def available_modes() -> list[str]:
    """argparse choices / streamlit selectbox용 mode 이름 리스트."""
    return list(MODE_REGISTRY.keys())


def get_mode_spec(name: str) -> ModeSpec:
    """알려지지 않은 mode면 ValueError — argparse choices가 1차로 막아야 함."""
    spec = MODE_REGISTRY.get(name)
    if spec is None:
        raise ValueError(
            f"unknown mode: {name}. available: {available_modes()}. "
            f"신규 mode는 ai_agent/backtest/modes.py의 MODE_REGISTRY에 등록하세요."
        )
    return spec
