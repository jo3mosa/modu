from typing import Any, Literal

from app.observability.langsmith_helpers import add_run_metadata
from app.state.investment_state import InvestmentAgentState
from app.utils.object_utils import get_value

RiskStatus = Literal["passed", "blocked", "hold"]


def _add_check(
    checks: list[dict[str, Any]],
    *,
    name: str,
    status: RiskStatus,
    reason: str,
    value: Any = None,
    limit: Any = None,
) -> None:
    checks.append(
        {
            "name": name,
            "status": status,
            "reason": reason,
            "value": value,
            "limit": limit,
        }
    )


def _make_result(
    *,
    status: RiskStatus,
    reason: str,
    checks: list[dict[str, Any]],
    risk_cleared: bool = False,
) -> dict[str, Any]:
    flow_status = "completed" if status == "passed" else status

    add_run_metadata({
        "node": "risk_gate",
        "status": status,
        "risk_cleared": risk_cleared,
    })

    return {
        "risk_cleared": risk_cleared,
        "risk_check_result": {
            "status": status,
            "reason": reason,
            "checks": checks,
        },
        "flow_status": flow_status,
    }


def risk_gate(state: InvestmentAgentState) -> dict[str, Any]:
    """
    Risk Gate.

    AI 판단 자체의 유효성만 검증한다.
    포트폴리오 비중, 현금 잔고, 종목 상태, 시장 상태 등
    실시간 데이터 의존 검증은 백엔드가 주문 실행 전에 수행한다.

    검증 항목:
    1. final_decision 기본값 유효성 (action / asset / side / order_amount)
    2. 자동매매 허용 여부 (allow_auto_trade)
    """

    checks: list[dict[str, Any]] = []

    decision = state.final_decision
    policy_context = state.policy_context or {}

    # ==============================
    # 1. final_decision 기본 검증
    # ==============================

    if decision is None:
        _add_check(
            checks,
            name="final_decision_exists",
            status="hold",
            reason="final_decision이 없습니다.",
        )
        return _make_result(
            status="hold",
            reason="최종 투자 결정이 없어 주문을 보류합니다.",
            checks=checks,
        )

    action = get_value(decision, "action")
    asset = get_value(decision, "asset")
    side = get_value(decision, "side")
    order_amount = get_value(decision, "order_amount") or 0

    if action == "hold":
        _add_check(
            checks,
            name="trade_action",
            status="hold",
            reason="최종 결정이 hold이므로 주문을 실행하지 않습니다.",
            value=action,
        )
        return _make_result(
            status="hold",
            reason="최종 투자 결정이 보류이므로 주문을 실행하지 않습니다.",
            checks=checks,
        )

    if action != "trade":
        _add_check(
            checks,
            name="trade_action",
            status="blocked",
            reason="알 수 없는 action 값입니다.",
            value=action,
            limit="trade | hold",
        )
        return _make_result(
            status="blocked",
            reason="최종 투자 결정 action 값이 유효하지 않아 주문을 차단합니다.",
            checks=checks,
        )

    if not asset:
        _add_check(
            checks,
            name="asset_exists",
            status="hold",
            reason="거래 대상 종목이 없습니다.",
        )
        return _make_result(
            status="hold",
            reason="거래 대상 종목이 없어 주문을 보류합니다.",
            checks=checks,
        )

    if side not in {"buy", "sell"}:
        _add_check(
            checks,
            name="order_side",
            status="hold",
            reason="주문 방향이 buy/sell 중 하나가 아닙니다.",
            value=side,
            limit="buy | sell",
        )
        return _make_result(
            status="hold",
            reason="주문 방향이 불명확하여 주문을 보류합니다.",
            checks=checks,
        )

    if order_amount <= 0:
        _add_check(
            checks,
            name="order_amount_positive",
            status="hold",
            reason="주문 금액이 0 이하입니다.",
            value=order_amount,
            limit="> 0",
        )
        return _make_result(
            status="hold",
            reason="주문 금액이 유효하지 않아 주문을 보류합니다.",
            checks=checks,
        )

    _add_check(
        checks,
        name="final_decision_basic_validation",
        status="passed",
        reason="최종 결정의 기본 필수값이 유효합니다.",
        value={
            "action": action,
            "asset": asset,
            "side": side,
            "order_amount": order_amount,
        },
    )

    # ==============================
    # 2. 자동매매 허용 정책 검증
    # ==============================

    allow_auto_trade = policy_context.get("allow_auto_trade", False)

    if not allow_auto_trade:
        _add_check(
            checks,
            name="auto_trade_policy",
            status="blocked",
            reason="자동매매 정책상 주문이 허용되지 않습니다.",
            value=allow_auto_trade,
            limit=True,
        )
        return _make_result(
            status="blocked",
            reason="자동매매 정책에 의해 주문이 차단되었습니다.",
            checks=checks,
        )

    _add_check(
        checks,
        name="auto_trade_policy",
        status="passed",
        reason="자동매매가 허용된 상태입니다.",
        value=allow_auto_trade,
    )

    # ==============================
    # 3. 최종 통과
    # ==============================

    return _make_result(
        status="passed",
        reason="Risk Gate 검증을 모두 통과했습니다.",
        checks=checks,
        risk_cleared=True,
    )
