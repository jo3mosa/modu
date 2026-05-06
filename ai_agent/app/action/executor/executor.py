import uuid

from app.action.executor.schemas import ExecutionResult
from app.action.order_request.builder import build_order_request
from app.state.investment_state import InvestmentAgentState


def executor(state: InvestmentAgentState) -> dict:
    """
    Executor 노드.

    역할:
    - Risk Guard를 통과한 최종 투자 결정을 실제 주문 요청으로 변환한다.
    - 주문 API를 호출한다. (현재 MVP 단계에서는 mock 실행)
    - 주문 결과를 execution_result에 저장한다.

    중요한 원칙:
    - Executor만 실제 주문 API 호출 권한을 가진다.
    - risk_cleared=True가 아니면 절대 주문하지 않는다.
    """

    order_request = build_order_request(
        final_decision=state.final_decision,
        risk_cleared=state.risk_cleared,
        portfolio_snapshot=state.portfolio_snapshot,
    )

    # 주문 요청 생성 실패
    if order_request is None:
        return {
            "execution_result": ExecutionResult(
                status="skipped",
                reason="주문 요청 생성 조건을 만족하지 않아 주문을 실행하지 않습니다.",
            ).model_dump(),
        }

    # ==============================
    # MVP 단계:
    # 실제 주문 API 대신 mock 주문 실행
    # ==============================

    mock_order_id = f"mock-{uuid.uuid4().hex[:12]}"

    return {
        "execution_result": ExecutionResult(
            status="success",
            order_id=mock_order_id,
            stock_code=order_request.stock_code,
            side=order_request.side,
            quantity=order_request.quantity,
            reason="Mock 주문 실행에 성공했습니다.",
        ).model_dump(),
    }