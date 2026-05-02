from typing import Any

from app.state.investment_state import InvestmentAgentState

MAX_EXECUTION_RETRY = 3


def executor(state: InvestmentAgentState) -> dict[str, Any]:
    """
    Executor

    역할:
    - Risk Guard를 통과한 final_decision을 실제 주문 실행으로 연결한다.
    - 현재는 실제 주문 API 대신 mock 주문 결과를 반환한다.
    - 나중에는 broker_service.place_order() 호출로 교체한다.

    주의:
    - LLM을 사용하지 않는다.
    - 투자 판단을 하지 않는다.
    - 주문 실행만 담당한다.

    입력:
    - final_decision
    - risk_cleared
    - execution_retry_count

    출력:
    - execution_result
    - flow_status
    - execution_retry_count
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

    decision = state.final_decision

    try:
        # TODO: 실제 broker_service.place_order()로 교체 예정
        execution_result = {
            "status": "success",
            "order_id": "MOCK-ORDER-001",
            "asset": decision.asset,
            "side": decision.side,
            "amount": decision.order_amount,
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
