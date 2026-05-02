from typing import Any

from app.state.investment_state import InvestmentAgentState


def memory_agent(state: InvestmentAgentState) -> dict[str, Any]:
    """
    Memory Agent.

    역할:
    - 현재 시장/종목 데이터와 사용자 정보를 바탕으로 판단에 필요한 문맥을 구성한다.
    - 실제 구현에서는 trade_logs DB, wiki, user profile, policy 문서를 조회한다.
    - 현재 skeleton에서는 외부 조회 없이 mock 데이터를 반환한다.

    입력:
    - market_snapshot
    - analysis_snapshot
    - candidate_assets

    출력:
    - memory_context
    - user_context
    - policy_context
    - history_context
    """

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