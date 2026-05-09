from typing import Any, TypedDict

from app.agents.memory.kb_loader import KnowledgeBaseLoader
from app.context.memory_context import (
    extract_key_signals,
    extract_sectors,
    extract_stock_codes,
    load_memory_context,
)
from app.context.user_context import (
    load_history_context,
    load_policy_context,
    load_user_context,
)
from app.memory.interfaces import MemoryStore


class AgentContext(TypedDict):
    """
    Reasoning Layer 에이전트에게 전달되는 사전 수집 컨텍스트.

    Context Loader가 그래프 실행 전에 조립하며,
    LLM 노드는 이 값을 읽기만 한다.
    """
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
        kb_loader: KnowledgeBaseLoader | None = None,
    ) -> None:
        self.memory_store = memory_store
        self.kb_loader = kb_loader or KnowledgeBaseLoader()

    def load(
        self,
        user_id: int,
        analysis_snapshot: dict[str, Any],
        candidate_assets: list[dict[str, Any]],
    ) -> AgentContext:
        stock_codes = extract_stock_codes(candidate_assets)
        sectors = extract_sectors(candidate_assets)
        key_signals = extract_key_signals(analysis_snapshot)

        user_context = load_user_context(user_id, self.kb_loader)
        policy_context = load_policy_context(user_id, self.kb_loader, user_context)
        memory_context = load_memory_context(
            user_id=user_id,
            stock_codes=stock_codes,
            sectors=sectors,
            key_signals=key_signals,
            memory_store=self.memory_store,
        )
        history_context = load_history_context(user_id, self.kb_loader, key_signals)

        return AgentContext(
            user_context=user_context,
            policy_context=policy_context,
            memory_context=memory_context,
            history_context=history_context,
        )
