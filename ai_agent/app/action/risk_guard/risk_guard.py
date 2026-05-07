from typing import Any, Literal

from app.state.investment_state import InvestmentAgentState
from app.utils.object_utils import get_value

RiskStatus = Literal["passed", "blocked", "hold", "approval_required"]


def _add_check(
    checks: list[dict[str, Any]],
    *,
    name: str,
    status: RiskStatus,
    reason: str,
    value: Any = None,
    limit: Any = None,
) -> None:
    """
    개별 리스크 검증 결과를 checks 리스트에 누적한다.

    Risk Guard는 단순히 True/False만 반환하면
    나중에 어떤 조건 때문에 주문이 막혔는지 알기 어렵다.

    그래서 각 검증 항목마다
    - name: 검증 항목 이름
    - status: passed / blocked / hold / approval_required
    - reason: 사람이 이해할 수 있는 사유
    - value: 실제 값
    - limit: 허용 기준
    을 남긴다.
    """

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
    approval_required: bool = False,
) -> dict[str, Any]:
    """
    Risk Guard의 최종 반환 형식을 통일한다.

    LangGraph에서는 각 노드가 state 전체를 직접 수정하는 대신,
    업데이트할 필드만 dict로 반환한다.

    Executor는 risk_cleared=True일 때만 주문을 실행해야 한다.
    """

    if status == "passed":
        flow_status = "running"
    elif status == "approval_required":
        flow_status = "hold"
    else:
        flow_status = status

    return {
        "risk_cleared": risk_cleared,
        "approval_required": approval_required,
        "risk_check_result": {
            "status": status,
            "reason": reason,
            "checks": checks,
        },
        "flow_status": flow_status,
    }


def _get_nested(data: dict[str, Any], *keys: str, default: Any = None) -> Any:
    """
    중첩 dict 값을 안전하게 조회한다.

    예:
    _get_nested(policy_context, "system_trading_constraints", "max_number_of_positions", default=3)
    """

    current: Any = data

    for key in keys:
        if not isinstance(current, dict):
            return default

        current = current.get(key)

        if current is None:
            return default

    return current


def _get_positions(portfolio_snapshot: dict[str, Any]) -> list[dict[str, Any]]:
    """
    portfolio_snapshot에서 보유 종목 목록을 가져온다.

    현재 프로젝트에서는 positions를 기본 키로 사용한다고 가정한다.
    혹시 holdings라는 이름으로 들어와도 MVP 단계에서는 같이 지원한다.
    """

    positions = portfolio_snapshot.get("positions")

    if positions is None:
        positions = portfolio_snapshot.get("holdings", [])

    if not isinstance(positions, list):
        return []

    return positions


def _find_position(
    positions: list[dict[str, Any]],
    asset: str,
) -> dict[str, Any] | None:
    """
    보유 종목 목록에서 특정 종목을 찾는다.

    position의 종목 키 이름이 ticker / asset / stock_code 중 무엇이든
    어느 정도 대응할 수 있게 처리한다.
    """

    for position in positions:
        position_asset = (
            position.get("ticker")
            or position.get("asset")
            or position.get("stock_code")
        )

        if position_asset == asset:
            return position

    return None


def _get_position_amount(position: dict[str, Any] | None) -> int:
    """
    특정 보유 종목의 평가금액을 가져온다.

    evaluation_amount=0도 정상 값일 수 있으므로,
    or 체인으로 fallback하지 않고 None일 때만 다음 후보 값을 확인한다.
    """

    if position is None:
        return 0

    amount = position.get("evaluation_amount")

    if amount is None:
        amount = position.get("amount")

    if amount is None:
        amount = position.get("market_value")

    if amount is None:
        return 0

    return int(amount)


def _get_position_quantity(position: dict[str, Any] | None) -> int:
    """
    특정 보유 종목의 보유 수량을 가져온다.

    quantity=0도 정상적으로 해석될 수 있으므로,
    None일 때만 기본값 0을 사용한다.
    """

    if position is None:
        return 0

    quantity = position.get("quantity")

    if quantity is None:
        return 0

    return int(quantity)


def _get_stock_risk_policy_key(asset_snapshot: dict[str, Any]) -> str:
    """
    종목 상태를 domestic_stock_risk_policy의 key로 변환한다.

    analysis_snapshot 또는 candidate_assets 안에 아래와 같은 값이 들어올 수 있다고 가정한다.
    - stock_status
    - risk_type
    - market_status

    값이 없으면 일반 상장주로 본다.
    """

    raw_status = (
        asset_snapshot.get("stock_status")
        or asset_snapshot.get("risk_type")
        or asset_snapshot.get("market_status")
        or "normal_listed_stock"
    )

    status_mapping = {
        "normal": "normal_listed_stock",
        "normal_listed_stock": "normal_listed_stock",
        "caution": "caution_or_warning_stock",
        "warning": "caution_or_warning_stock",
        "caution_or_warning_stock": "caution_or_warning_stock",
        "administrative": "administrative_issue_stock",
        "administrative_issue_stock": "administrative_issue_stock",
        "halt": "trading_halt_stock",
        "trading_halt": "trading_halt_stock",
        "trading_halt_stock": "trading_halt_stock",
    }

    return status_mapping.get(str(raw_status), "normal_listed_stock")


def _find_asset_snapshot(
    state: InvestmentAgentState,
    asset: str,
) -> dict[str, Any]:
    """
    candidate_assets 또는 analysis_snapshot에서 특정 종목의 분석 정보를 찾는다.

    Risk Guard는 외부 API를 직접 조회하지 않는다.
    따라서 그래프 실행 전에 state에 들어온 스냅샷만 사용한다.
    """

    for candidate in state.candidate_assets:
        candidate_asset = (
            candidate.get("asset")
            or candidate.get("ticker")
            or candidate.get("stock_code")
        )

        if candidate_asset == asset:
            return candidate

    analysis_snapshot = state.analysis_snapshot or {}

    if asset in analysis_snapshot and isinstance(analysis_snapshot[asset], dict):
        return analysis_snapshot[asset]

    stock_code = analysis_snapshot.get("stock_code")
    if stock_code == asset:
        return analysis_snapshot

    return {}


def risk_guard(state: InvestmentAgentState) -> dict[str, Any]:
    """
    Risk Guard Agent.

    역할:
    - Supervisor Agent가 만든 final_decision을 실제 주문으로 넘겨도 되는지 검증한다.
    - 사용자 투자 성향, 서비스 공통 정책, 계좌 스냅샷, 시장 상태를 확인한다.
    - 검증 실패 시 Executor로 넘어가지 않도록 risk_cleared=False를 반환한다.

    중요한 원칙:
    - Risk Guard는 주문 API를 호출하지 않는다.
    - Risk Guard는 외부 broker API를 직접 조회하지 않는다.
    - 이미 state에 주입된 portfolio_snapshot, market_snapshot, policy_context만 사용한다.
    - Executor는 risk_cleared=True일 때만 주문을 실행해야 한다.
    """

    checks: list[dict[str, Any]] = []

    decision = state.final_decision
    user_context = state.user_context or {}
    policy_context = state.policy_context or {}
    portfolio_snapshot = state.portfolio_snapshot or {}
    market_snapshot = state.market_snapshot or {}

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
            limit="trade",
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
    # 3. Kill-switch 검증
    # ==============================

    kill_switch = policy_context.get("kill_switch", {})

    if isinstance(kill_switch, dict):
        kill_switch_enabled = kill_switch.get("enabled", False)
        kill_switch_triggered = kill_switch.get("triggered", False)

        if kill_switch_enabled and kill_switch_triggered:
            _add_check(
                checks,
                name="kill_switch",
                status="blocked",
                reason=kill_switch.get("reason", "Kill-switch 조건이 충족되었습니다."),
                value=kill_switch,
            )
            return _make_result(
                status="blocked",
                reason="Kill-switch가 발동되어 자동매매를 중단합니다.",
                checks=checks,
            )

    _add_check(
        checks,
        name="kill_switch",
        status="passed",
        reason="Kill-switch 조건에 해당하지 않습니다.",
    )

    # ==============================
    # 4. 최대 주문 금액 검증
    # ==============================

    max_order_amount = user_context.get("max_order_amount")

    if max_order_amount is None:
        max_order_amount = policy_context.get("max_order_amount")

    if max_order_amount is None:
        _add_check(
            checks,
            name="max_order_amount_config",
            status="hold",
            reason="최대 주문 금액 설정이 없습니다.",
        )
        return _make_result(
            status="hold",
            reason="최대 주문 금액 설정이 없어 주문을 보류합니다.",
            checks=checks,
        )

    if order_amount > max_order_amount:
        _add_check(
            checks,
            name="max_order_amount",
            status="blocked",
            reason="최대 주문 금액을 초과했습니다.",
            value=order_amount,
            limit=max_order_amount,
        )
        return _make_result(
            status="blocked",
            reason="최대 주문 금액 초과로 주문을 차단합니다.",
            checks=checks,
        )

    _add_check(
        checks,
        name="max_order_amount",
        status="passed",
        reason="최대 주문 금액 이내입니다.",
        value=order_amount,
        limit=max_order_amount,
    )

    # ==============================
    # 5. 종목 위험 등급 / 거래정지 / 관리종목 검증
    # ==============================

    asset_snapshot = _find_asset_snapshot(state, asset)
    if side == "buy" and not asset_snapshot:
        _add_check(
            checks,
            name="asset_snapshot_exists",
            status="hold",
            reason="종목 상태 스냅샷이 없어 거래정지/관리종목 여부를 확인할 수 없습니다.",
            value=asset,
        )
        return _make_result(
            status="hold",
            reason="종목 상태 정보 누락으로 매수 주문을 보류합니다.",
            checks=checks,
        )

    stock_policy_key = _get_stock_risk_policy_key(asset_snapshot)

    auto_buy_policy = _get_nested(
        user_context,
        "domestic_stock_risk_policy",
        stock_policy_key,
        "auto_buy_policy",
        default=None,
    )

    if auto_buy_policy is None:
        auto_buy_policy = _get_nested(
            policy_context,
            "domestic_stock_risk_policy",
            stock_policy_key,
            "auto_buy_policy",
            default="allowed_with_constraints",
        )

    if side == "buy" and auto_buy_policy == "block":
        _add_check(
            checks,
            name="domestic_stock_risk_policy",
            status="blocked",
            reason="종목 위험 정책상 자동 매수가 금지된 종목입니다.",
            value={
                "stock_policy_key": stock_policy_key,
                "auto_buy_policy": auto_buy_policy,
            },
            limit="not block",
        )
        return _make_result(
            status="blocked",
            reason="고위험 또는 거래 제한 종목으로 매수 주문을 차단합니다.",
            checks=checks,
        )

    _add_check(
        checks,
        name="domestic_stock_risk_policy",
        status="passed",
        reason="종목 위험 정책을 통과했습니다.",
        value={
            "stock_policy_key": stock_policy_key,
            "auto_buy_policy": auto_buy_policy,
        },
    )

    # ==============================
    # 6. 포트폴리오 스냅샷 검증
    # ==============================

    total_asset = portfolio_snapshot.get("total_asset")
    if total_asset is None:
        total_asset = portfolio_snapshot.get("total_assets")

    cash = portfolio_snapshot.get("cash")
    if cash is None:
        cash = portfolio_snapshot.get("cash_balance")

    positions = _get_positions(portfolio_snapshot)
    current_position = _find_position(positions, asset)

    if total_asset is None or total_asset <= 0:
        _add_check(
            checks,
            name="total_asset",
            status="hold",
            reason="총자산 정보가 없거나 0 이하입니다.",
            value=total_asset,
        )
        return _make_result(
            status="hold",
            reason="총자산 정보가 없어 주문을 보류합니다.",
            checks=checks,
        )

    if cash is None or cash < 0:
        _add_check(
            checks,
            name="cash_balance_exists",
            status="hold",
            reason="현금 잔고 정보가 없거나 음수입니다.",
            value=cash,
        )
        return _make_result(
            status="hold",
            reason="현금 잔고 정보가 없어 주문을 보류합니다.",
            checks=checks,
        )

    _add_check(
        checks,
        name="portfolio_snapshot_exists",
        status="passed",
        reason="포트폴리오 스냅샷 필수값이 존재합니다.",
        value={
            "total_asset": total_asset,
            "cash": cash,
            "positions_count": len(positions),
        },
    )

    # ==============================
    # 7. 매수 전용 검증
    # ==============================

    if side == "buy":
        if cash < order_amount:
            _add_check(
                checks,
                name="cash_balance",
                status="blocked",
                reason="현금 잔고가 주문 금액보다 부족합니다.",
                value=cash,
                limit=order_amount,
            )
            return _make_result(
                status="blocked",
                reason="현금 잔고 부족으로 매수 주문을 차단합니다.",
                checks=checks,
            )

        _add_check(
            checks,
            name="cash_balance",
            status="passed",
            reason="현금 잔고가 주문 금액 이상입니다.",
            value=cash,
            limit=order_amount,
        )

        minimum_cash_ratio = _get_nested(
            policy_context,
            "system_trading_constraints",
            "minimum_cash_ratio",
            "value",
            default=None,
        )

        if minimum_cash_ratio is None:
            minimum_cash_ratio = _get_nested(
                policy_context,
                "asset_allocation",
                "minimum_cash_ratio",
                default=10,
            )

        cash_after_order = cash - order_amount
        cash_ratio_after_order = (cash_after_order / total_asset) * 100

        if cash_ratio_after_order < minimum_cash_ratio:
            _add_check(
                checks,
                name="minimum_cash_ratio",
                status="blocked",
                reason="주문 후 최소 현금 비중을 만족하지 못합니다.",
                value=round(cash_ratio_after_order, 2),
                limit=minimum_cash_ratio,
            )
            return _make_result(
                status="blocked",
                reason="최소 현금 비중 미달로 매수 주문을 차단합니다.",
                checks=checks,
            )

        _add_check(
            checks,
            name="minimum_cash_ratio",
            status="passed",
            reason="주문 후에도 최소 현금 비중을 만족합니다.",
            value=round(cash_ratio_after_order, 2),
            limit=minimum_cash_ratio,
        )

        investor_type = _get_nested(
            user_context,
            "investor_type",
            "code",
            default="active",
        )

        max_single_stock_ratio = _get_nested(
            policy_context,
            "system_trading_constraints",
            "investor_type_constraints",
            investor_type,
            "max_single_stock_ratio",
            default=None,
        )

        if max_single_stock_ratio is None:
            max_single_stock_ratio = _get_nested(
                policy_context,
                "asset_allocation",
                "max_single_stock_ratio",
                default=20,
            )

        current_position_amount = _get_position_amount(current_position)
        stock_ratio_after_order = (
            (current_position_amount + order_amount) / total_asset
        ) * 100

        if stock_ratio_after_order > max_single_stock_ratio:
            _add_check(
                checks,
                name="max_single_stock_ratio",
                status="blocked",
                reason="종목당 최대 투자 비율을 초과합니다.",
                value=round(stock_ratio_after_order, 2),
                limit=max_single_stock_ratio,
            )
            return _make_result(
                status="blocked",
                reason="단일 종목 투자 비중 초과로 매수 주문을 차단합니다.",
                checks=checks,
            )

        _add_check(
            checks,
            name="max_single_stock_ratio",
            status="passed",
            reason="종목당 최대 투자 비율 이내입니다.",
            value=round(stock_ratio_after_order, 2),
            limit=max_single_stock_ratio,
        )

        max_number_of_positions = _get_nested(
            policy_context,
            "system_trading_constraints",
            "max_number_of_positions",
            default=None,
        )

        if max_number_of_positions is None:
            max_number_of_positions = _get_nested(
                policy_context,
                "asset_allocation",
                "max_number_of_positions",
                default=3,
            )

        already_holding = current_position is not None

        if not already_holding and len(positions) >= max_number_of_positions:
            _add_check(
                checks,
                name="max_number_of_positions",
                status="blocked",
                reason="최대 보유 종목 수를 초과합니다.",
                value=len(positions) + 1,
                limit=max_number_of_positions,
            )
            return _make_result(
                status="blocked",
                reason="최대 보유 종목 수 초과로 매수 주문을 차단합니다.",
                checks=checks,
            )

        _add_check(
            checks,
            name="max_number_of_positions",
            status="passed",
            reason="최대 보유 종목 수 제한을 만족합니다.",
            value=len(positions) if already_holding else len(positions) + 1,
            limit=max_number_of_positions,
        )

    # ==============================
    # 8. 매도 전용 검증
    # ==============================

    if side == "sell":
        quantity = get_value(decision, "quantity")

        # 현재 FinalDecision에는 quantity 필드가 없다.
        # 따라서 MVP에서는 order_amount를 매도 금액으로 보고,
        # 보유 평가금액을 초과하지 않는지만 검증한다.
        holding_amount = _get_position_amount(current_position)
        holding_quantity = _get_position_quantity(current_position)

        if current_position is None:
            _add_check(
                checks,
                name="holding_position",
                status="blocked",
                reason="매도하려는 종목을 보유하고 있지 않습니다.",
                value=asset,
            )
            return _make_result(
                status="blocked",
                reason="보유하지 않은 종목이므로 매도 주문을 차단합니다.",
                checks=checks,
            )

        if quantity is not None:
            if quantity > holding_quantity:
                _add_check(
                    checks,
                    name="sell_quantity",
                    status="blocked",
                    reason="매도 수량이 보유 수량보다 많습니다.",
                    value=quantity,
                    limit=holding_quantity,
                )
                return _make_result(
                    status="blocked",
                    reason="보유 수량 초과로 매도 주문을 차단합니다.",
                    checks=checks,
                )

            _add_check(
                checks,
                name="sell_quantity",
                status="passed",
                reason="매도 수량이 보유 수량 이내입니다.",
                value=quantity,
                limit=holding_quantity,
            )

        else:
            if order_amount > holding_amount:
                _add_check(
                    checks,
                    name="sell_order_amount",
                    status="blocked",
                    reason="매도 금액이 보유 평가금액보다 큽니다.",
                    value=order_amount,
                    limit=holding_amount,
                )
                return _make_result(
                    status="blocked",
                    reason="보유 금액 초과로 매도 주문을 차단합니다.",
                    checks=checks,
                )

            _add_check(
                checks,
                name="sell_order_amount",
                status="passed",
                reason="매도 금액이 보유 평가금액 이내입니다.",
                value=order_amount,
                limit=holding_amount,
            )

    # ==============================
    # 9. 시장 상태 검증
    # ==============================

    market_open = market_snapshot.get("market_open")
    kospi_change_rate = market_snapshot.get("kospi_change_rate")
    kosdaq_change_rate = market_snapshot.get("kosdaq_change_rate")

    if market_open is None:
        _add_check(
            checks,
            name="market_open_exists",
            status="hold",
            reason="장 운영 여부 정보가 없어 주문 가능 시간을 판단할 수 없습니다.",
            value=market_open,
            limit="True | False",
        )
        return _make_result(
            status="hold",
            reason="장 운영 여부 정보 누락으로 주문을 보류합니다.",
            checks=checks,
        )

    if not market_open:
        _add_check(
            checks,
            name="market_open",
            status="blocked",
            reason="현재 장 운영 시간이 아니므로 주문을 차단합니다.",
            value=market_open,
            limit=True,
        )
        return _make_result(
            status="blocked",
            reason="장 운영 시간이 아니므로 주문을 차단합니다.",
            checks=checks,
        )

    _add_check(
        checks,
        name="market_open",
        status="passed",
        reason="현재 장 운영 시간입니다.",
        value=market_open,
    )

    if side == "buy":
        if kospi_change_rate is None or kosdaq_change_rate is None:
            _add_check(
                checks,
                name="market_index_snapshot",
                status="hold",
                reason="KOSPI/KOSDAQ 등락률 정보가 없어 시장 급락 여부를 판단할 수 없습니다.",
                value={
                    "kospi_change_rate": kospi_change_rate,
                    "kosdaq_change_rate": kosdaq_change_rate,
                },
            )
            return _make_result(
                status="hold",
                reason="시장 지수 정보 누락으로 신규 매수를 보류합니다.",
                checks=checks,
            )

        market_drop_threshold = (
            policy_context
            .get("market_rules", {})
            .get("market_drop_threshold", -3.0)
        )

        if kospi_change_rate <= market_drop_threshold or kosdaq_change_rate <= market_drop_threshold:
            _add_check(
                checks,
                name="market_drop",
                status="blocked",
                reason="시장 지수가 급락 기준 이하입니다.",
                value={
                    "kospi_change_rate": kospi_change_rate,
                    "kosdaq_change_rate": kosdaq_change_rate,
                },
                limit=market_drop_threshold,
            )
            return _make_result(
                status="blocked",
                reason="시장 급락 조건으로 신규 매수를 차단합니다.",
                checks=checks,
            )

        _add_check(
            checks,
            name="market_drop",
            status="passed",
            reason="시장 지수가 급락 기준을 넘지 않았습니다.",
            value={
                "kospi_change_rate": kospi_change_rate,
                "kosdaq_change_rate": kosdaq_change_rate,
            },
            limit=market_drop_threshold,
        )

    # ==============================
    # 10. 고위험 조건 사용자 승인 처리
    # ==============================

    critic_feedback = state.critic_feedback
    risk_level = get_value(critic_feedback, "risk_level")

    if risk_level == "high":
        _add_check(
            checks,
            name="critic_risk_level",
            status="approval_required",
            reason="Critic Agent가 high risk로 평가하여 사용자 승인이 필요합니다.",
            value=risk_level,
            limit="low | medium",
        )
        return _make_result(
            status="approval_required",
            reason="고위험 거래로 분류되어 사용자 승인이 필요합니다.",
            checks=checks,
            approval_required=True,
        )

    _add_check(
        checks,
        name="critic_risk_level",
        status="passed",
        reason="사용자 승인 없이 진행 가능한 리스크 수준입니다.",
        value=risk_level,
        limit="low | medium",
    )

    # ==============================
    # 12. 최종 통과
    # ==============================

    return _make_result(
        status="passed",
        reason="Risk Guard 검증을 모두 통과했습니다.",
        checks=checks,
        risk_cleared=True,
    )