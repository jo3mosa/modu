from typing import Any

from app.state.investment_state import InvestmentAgentState


def memory_agent(state: InvestmentAgentState) -> dict[str, Any]:
    return {
        "memory_context": "최근 유사 신호에서는 급등 직후 진입 시 손실 가능성이 높았습니다.",
        "user_context": {
            "risk_level": "moderate",
            "max_order_amount": 1_000_000,
            "max_position_ratio": 0.2,
            "stop_loss_rate": -0.05,
        },
        "policy_context": {
            "allow_auto_trade": True,
            "kill_switch_enabled": True,
        },
        "history_context": "과거 반도체 섹터에서는 RSI 회복 구간의 1차 분할 매수가 유효했습니다.",
    }