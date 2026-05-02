from typing import Any

from app.state.investment_state import InvestmentAgentState
from app.state.schemas import StrategyDraft


def strategy_agent(state: InvestmentAgentState) -> dict[str, Any]:
    """
    Strategy Agent.

    역할:
    - Analysis Layer 결과와 Memory Agent가 만든 문맥을 기반으로 투자 전략 초안을 생성한다.
    - 현재는 LLM 없이 mock 전략을 반환한다.
    - 나중에는 analysis_snapshot, memory_context, user_context, portfolio_snapshot을 prompt에 넣어 LLM 호출 예정

    입력:
    - analysis_snapshot
    - candidate_assets
    - portfolio_snapshot
    - memory_context
    - user_context
    - policy_context

    출력:
    - strategy_draft
    """
    
    asset = state.candidate_assets[0] if state.candidate_assets else {}

    return {
        "strategy_draft": StrategyDraft(
            asset=asset.get("symbol", "UNKNOWN"),
            side="buy",
            order_amount=500_000,
            target_price=78_000,
            stop_loss_price=69_000,
            reason="기술적 신호와 과거 유사 패턴을 기반으로 1차 매수 전략을 생성했습니다.",
            confidence=0.72,
        )
    }
