from typing import Any

from app.state.investment_state import InvestmentAgentState
from app.state.schemas import CriticFeedback


def critic_agent(state: InvestmentAgentState) -> dict[str, Any]:
    """
    Critic Agent

    역할:
    - Strategy Agent가 만든 strategy_draft를 검토한다.
    - 변동성, 과열, 사용자 리스크 성향, 과도한 집중 투자 여부를 점검한다.
    - 현재는 mock 피드백을 반환한다.

    입력:
    - strategy_draft
    - user_context
    - policy_context
    - history_context

    출력:
    - critic_feedback
    """

    return {
        "critic_feedback": CriticFeedback(
            approved=True,
            risk_level="medium",
            comments=[
                "주문 금액은 사용자 한도 이내입니다.",
                "손절가가 명확하게 설정되어 있습니다.",
                "급등 직후 진입 여부는 추가 모니터링이 필요합니다.",
            ],
        )
    }
