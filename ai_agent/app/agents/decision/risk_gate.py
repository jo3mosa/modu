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


def _find_avg_price(
    portfolio_snapshot: dict[str, Any] | None, asset: str | None,
) -> float | None:
    """portfolio_snapshot에서 보유 종목 평단가 조회.

    키 컨벤션이 두 종류로 공존하므로 둘 다 시도한다:
      - production (portfolio_snapshot_repository): "positions"
      - mock (mock_trigger):                        "holdings"
    """
    if not portfolio_snapshot or not asset:
        return None
    for key in ("positions", "holdings"):
        items = portfolio_snapshot.get(key)
        if not items:
            continue
        for item in items:
            if not isinstance(item, dict):
                continue
            if item.get("stock_code") == asset:
                price = item.get("average_price")
                if price is not None and price > 0:
                    return float(price)
    return None


def risk_gate(state: InvestmentAgentState) -> dict[str, Any]:
    """
    Risk Gate (AI 측 1차 검증 게이트).

    AI 판단의 형식 유효성과 사용자 한도(손절·익절·AI 운용 금액) 정합성을
    1차 hard rule로 검증한다. 백엔드 SignalHandlerService가 실시간 데이터로
    2차 hard rule을 수행한다 (이중 게이트).

    검증 항목:
    1. final_decision 기본값 유효성 (action / asset / side / order_amount)
    2. 자동매매 허용 여부 (allow_auto_trade)
    3. 사용자 한도 정합성 (hard rule):
       3-1. BUY 손절 정합성 — user_context.risk_rules.stop_loss_pct vs target_price 기준 손절가
       3-2. SELL 익절 정합성 — user_context.risk_rules.take_profit_pct vs 평단가 기준 목표가
       3-3. 단일 주문 AI 운용 한도 — user_context.risk_rules.ai_budget_amount vs order_amount
            * BE 컬럼(trading_rules.ai_budget_amount) 도입 전에는 값이 None → skip
            * 일일 누적 한도 검증은 백엔드 SignalHandlerService 영역
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
    # 3. 사용자 한도 정합성 검증 (hard rule)
    # ==============================
    # AI 1차 검증. 백엔드가 실시간 데이터로 2차 hard rule 검증한다 (이중 게이트).
    # 데이터(사용자 설정값 또는 평단가)가 없으면 검증 skip — backward compat.
    # 단위 가정: stop_loss_pct / take_profit_pct는 % 단위 (예: 5 = 5%).
    # 백엔드 trading_rules의 BIGINT 컬럼 단위 컨벤션 일치 필요.

    risk_rules = (state.user_context or {}).get("risk_rules") or {}
    target_price = get_value(decision, "target_price")
    stop_loss_price = get_value(decision, "stop_loss_price")

    # ── 3-1. BUY 손절 정합성 ────────────────────────────────────────
    if side == "buy":
        stop_loss_pct = risk_rules.get("stop_loss_pct")
        if (
            stop_loss_pct is not None and stop_loss_pct > 0
            and target_price is not None and target_price > 0
            and stop_loss_price is not None and stop_loss_price > 0
        ):
            min_acceptable_stop = float(target_price) * (1.0 - float(stop_loss_pct) / 100.0)
            if float(stop_loss_price) < min_acceptable_stop:
                _add_check(
                    checks,
                    name="buy_stop_loss_alignment",
                    status="blocked",
                    reason=f"AI 손절가가 사용자 허용 손실률({stop_loss_pct}%)을 초과합니다.",
                    value=stop_loss_price,
                    limit=min_acceptable_stop,
                )
                return _make_result(
                    status="blocked",
                    reason="AI 손절가가 사용자 손절률을 위반하여 주문을 차단합니다.",
                    checks=checks,
                )
            _add_check(
                checks,
                name="buy_stop_loss_alignment",
                status="passed",
                reason="AI 손절가가 사용자 손절률 범위 내입니다.",
                value=stop_loss_price,
                limit=min_acceptable_stop,
            )

    # ── 3-2. SELL 익절 정합성 ───────────────────────────────────────
    if side == "sell":
        take_profit_pct = risk_rules.get("take_profit_pct")
        avg_price = _find_avg_price(state.portfolio_snapshot, asset)
        if (
            take_profit_pct is not None and take_profit_pct > 0
            and avg_price is not None and avg_price > 0
            and target_price is not None and target_price > 0
        ):
            min_acceptable_target = float(avg_price) * (1.0 + float(take_profit_pct) / 100.0)
            if float(target_price) < min_acceptable_target:
                _add_check(
                    checks,
                    name="sell_take_profit_alignment",
                    status="blocked",
                    reason=(
                        f"AI 매도 목표가가 사용자 익절 목표 수익률({take_profit_pct}%)에 미달합니다."
                    ),
                    value=target_price,
                    limit=min_acceptable_target,
                )
                return _make_result(
                    status="blocked",
                    reason="AI 매도 목표가가 사용자 익절률에 미달하여 주문을 차단합니다.",
                    checks=checks,
                )
            _add_check(
                checks,
                name="sell_take_profit_alignment",
                status="passed",
                reason="AI 매도 목표가가 사용자 익절률을 충족합니다.",
                value=target_price,
                limit=min_acceptable_target,
            )

    # ── 3-3. AI 운용 한도 — 단일 주문 ────────────────────────────────
    # 누적(오늘 누적 매수액 vs ai_budget_amount) 검증은 백엔드 영역.
    # BE의 trading_rules.ai_budget_amount 컬럼 도입 전엔 값이 None → skip.
    ai_budget_amount = risk_rules.get("ai_budget_amount")
    if ai_budget_amount is not None and ai_budget_amount > 0:
        if order_amount > ai_budget_amount:
            _add_check(
                checks,
                name="ai_budget_single_order",
                status="blocked",
                reason="단일 주문 금액이 AI 운용 한도를 초과합니다.",
                value=order_amount,
                limit=ai_budget_amount,
            )
            return _make_result(
                status="blocked",
                reason="단일 주문 금액이 AI 운용 한도를 초과하여 주문을 차단합니다.",
                checks=checks,
            )
        _add_check(
            checks,
            name="ai_budget_single_order",
            status="passed",
            reason="단일 주문 금액이 AI 운용 한도 이내입니다.",
            value=order_amount,
            limit=ai_budget_amount,
        )

    # ==============================
    # 4. 최종 통과
    # ==============================

    return _make_result(
        status="passed",
        reason="Risk Gate 검증을 모두 통과했습니다.",
        checks=checks,
        risk_cleared=True,
    )
