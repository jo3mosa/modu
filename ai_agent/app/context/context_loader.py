from functools import lru_cache
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
from app.memory.db_store import DBMemoryStore
from app.memory.interfaces import MemoryStore
from app.observability.langsmith_helpers import add_run_metadata
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


@lru_cache(maxsize=1)
def _get_shared_engine() -> Engine:
    """프로세스 전체에서 Engine을 한 번만 생성해 connection pool을 재사용한다."""
    return create_engine_from_env()


def context_loader(state: InvestmentAgentState) -> dict[str, Any]:
    """LangGraph 노드 어댑터."""
    if state.user_id is None:
        raise ValueError("InvestmentAgentState.user_id가 설정되지 않았습니다.")

    engine = _get_shared_engine()
    memory_store = DBMemoryStore(engine)
    add_run_metadata({
        "node": "context_loader",
        "memory_backend": memory_store.__class__.__name__,
    })

    loader = ContextLoader(memory_store=memory_store, engine=engine)
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
