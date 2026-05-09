from typing import Any, TypedDict

from sqlalchemy.engine import Engine

from app.context.memory_context import (
    extract_key_signals,
    extract_sectors,
    extract_stock_codes,
    load_memory_context,
)
from app.context.user_context import (
    create_engine_from_env,
    load_history_context,
    load_policy_context,
    load_user_context,
)
from app.memory.interfaces import MemoryStore, PastDecision
from app.state.investment_state import InvestmentAgentState


class AgentContext(TypedDict):
    """Reasoning Layer 에이전트에게 전달되는 사전 수집 컨텍스트."""
    user_context: dict[str, Any]
    policy_context: dict[str, Any]
    memory_context: dict[str, Any]
    history_context: dict[str, Any]


class ContextLoader:
    """
    Reasoning Layer 실행 전 필요한 모든 컨텍스트를 수집·조립하는 모듈.

    LLM을 사용하지 않는 결정론적 데이터 수집 레이어이며,
    user_context / memory_context 모듈에 위임한다.
    """

    def __init__(
        self,
        memory_store: MemoryStore,
        engine: Engine | None = None,
    ) -> None:
        self.memory_store = memory_store
        # engine을 주입하지 않으면 DATABASE_URL 환경변수로 생성
        self.engine = engine or create_engine_from_env()

    def load(
        self,
        user_id: int,
        analysis_snapshot: dict[str, Any],
        candidate_assets: list[dict[str, Any]],
    ) -> AgentContext:
        stock_codes = extract_stock_codes(candidate_assets)
        sectors = extract_sectors(candidate_assets)
        key_signals = extract_key_signals(analysis_snapshot)

        user_context = load_user_context(user_id, self.engine)
        policy_context = load_policy_context(user_id, self.engine)
        memory_context = load_memory_context(
            user_id=user_id,
            stock_codes=stock_codes,
            sectors=sectors,
            key_signals=key_signals,
            memory_store=self.memory_store,
            days=30,
        )
        history_context = load_history_context(user_id, key_signals)

        return AgentContext(
            user_context=user_context,
            policy_context=policy_context,
            memory_context=memory_context,
            history_context=history_context,
        )


class _NullMemoryStore:
    """DBMemoryStore 구현 전 임시 stub. db_store.py 완성 후 교체."""

    def get_recent_decisions(
        self,
        user_id: int,
        stock_codes: list[str],
        sectors: list[str],
        key_signals: list[str],
        limit: int = 10,
        days: int = 30,
    ) -> list[PastDecision]:
        return []

    def get_similar_decisions(
        self,
        user_id: int,
        stock_codes: list[str],
        sectors: list[str],
        key_signals: list[str],
        limit: int = 5,
        days: int = 30,
        only_loss: bool = False,
    ) -> list[PastDecision]:
        return []

    def store_decision(self, log: Any) -> int:
        return 0

    def store_postmortem(self, report: Any) -> int:
        return 0


def context_loader(state: InvestmentAgentState) -> dict[str, Any]:
    """
    LangGraph 노드 어댑터.

    TODO: _NullMemoryStore를 DBMemoryStore로 교체.
    """
    if state.user_id is None:
        raise ValueError("InvestmentAgentState.user_id가 설정되지 않았습니다.")

    loader = ContextLoader(memory_store=_NullMemoryStore())
    ctx = loader.load(
        user_id=state.user_id,
        analysis_snapshot=state.analysis_snapshot,
        candidate_assets=state.candidate_assets,
    )

    return {
        "user_context": ctx["user_context"],
        "policy_context": ctx["policy_context"],
        "memory_context": ctx["memory_context"],
        "history_context": ctx["history_context"],
    }
