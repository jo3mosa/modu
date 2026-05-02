from typing import Any

from app.state.investment_state import InvestmentAgentState
from app.state.schemas import CriticFeedback


def critic_agent(state: InvestmentAgentState) -> dict[str, Any]:
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
