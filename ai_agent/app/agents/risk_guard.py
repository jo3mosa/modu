from typing import Any

from app.state.investment_state import InvestmentAgentState


def risk_guard(state: InvestmentAgentState) -> dict[str, Any]:
    decision = state.final_decision
    user_context = state.user_context
    policy_context = state.policy_context

    if decision is None or decision.action != "trade":
        return {
            "risk_cleared": False,
            "risk_check_result": {
                "status": "blocked",
                "reason": "최종 결정이 trade가 아닙니다.",
            },
            "flow_status": "blocked",
        }

    if not policy_context.get("allow_auto_trade", False):
        return {
            "risk_cleared": False,
            "risk_check_result": {
                "status": "blocked",
                "reason": "자동매매 정책상 주문이 허용되지 않습니다.",
            },
            "flow_status": "blocked",
        }

    if (decision.order_amount or 0) > user_context.get("max_order_amount", 0):
        return {
            "risk_cleared": False,
            "risk_check_result": {
                "status": "blocked",
                "reason": "최대 주문 금액을 초과했습니다.",
            },
            "flow_status": "blocked",
        }

    return {
        "risk_cleared": True,
        "risk_check_result": {
            "status": "passed",
            "reason": "사용자 리스크 룰과 정책 조건을 통과했습니다.",
        },
    }
