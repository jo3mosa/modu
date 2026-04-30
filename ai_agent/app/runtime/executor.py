from typing import Any

from app.state.investment_state import InvestmentAgentState

MAX_EXECUTION_RETRY = 3


def executor(state: InvestmentAgentState) -> dict[str, Any]:
    """
    LLM을 사용하지 않는 주문 실행 전용 모듈.
    Risk Guard를 통과한 final_decision만 실행한다.
    """

    if not state.risk_cleared:
        return {
            "execution_result": {
                "status": "skipped",
                "reason": "Risk Guard 미통과로 주문을 실행하지 않았습니다.",
            },
            "flow_status": "blocked",
        }

    if state.execution_retry_count >= MAX_EXECUTION_RETRY:
        return {
            "execution_result": {
                "status": "failed",
                "reason": "최대 주문 재시도 횟수를 초과했습니다.",
            },
            "flow_status": "failed",
        }

    try:
        # TODO: 실제 broker_service.place_order()로 교체 예정
        execution_result = {
            "status": "success",
            "order_id": "MOCK-ORDER-001",
            "asset": state.final_decision.get("asset"),
            "side": state.final_decision.get("side"),
            "amount": state.final_decision.get("order_amount"),
        }

        return {
            "execution_result": execution_result,
            "flow_status": "completed",
        }

    except Exception as exc:
        return {
            "execution_retry_count": state.execution_retry_count + 1,
            "execution_result": {
                "status": "retry_pending",
                "reason": str(exc),
            },
            "flow_status": "running",
        }