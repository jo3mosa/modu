from typing import Any

from app.action.order_request.schemas import OrderRequest
from app.state.schemas import FinalDecision
from app.utils.object_utils import get_value


def build_order_request(
    final_decision: FinalDecision | None,
    risk_cleared: bool,
    portfolio_snapshot: dict[str, Any] | None = None,
) -> OrderRequest | None:
    """
    FinalDecision을 Executor가 사용할 OrderRequest로 변환한다.

    이 함수는 투자 판단을 새로 하지 않는다.

    역할:
    - Risk Guard 통과 여부 확인
    - trade/hold 여부 확인
    - 주문 대상 종목 확인
    - 주문 방향 확인
    - 주문 수량 계산
    - Executor가 사용할 주문 요청 객체 생성
    """

    portfolio_snapshot = portfolio_snapshot or {}

    # Risk Guard를 통과하지 못한 결정은 주문으로 만들지 않는다.
    if not risk_cleared:
        return None

    # final_decision 자체가 없으면 주문 생성 불가.
    if final_decision is None:
        return None

    action = final_decision.action

    # 현재 FinalDecision 구조에서는 실제 주문 여부를 action으로 판단한다.
    # trade만 주문 대상이고, hold는 주문하지 않는다.
    if action != "trade":
        return None

    stock_code = final_decision.asset
    side = final_decision.side
    order_amount = final_decision.order_amount

    if not stock_code:
        return None

    if side not in {"buy", "sell"}:
        return None

    safe_order_amount = _safe_to_int(order_amount)

    if safe_order_amount <= 0:
        return None

    # MVP에서는 target_price를 주문 수량 계산 기준 가격으로 사용한다.
    # 나중에 현재가 스냅샷이 붙으면 current_price 우선으로 바꾸면 된다.
    reference_price = _resolve_reference_price(
        final_decision=final_decision,
        portfolio_snapshot=portfolio_snapshot,
        stock_code=stock_code,
    )

    if reference_price <= 0:
        return None

    quantity = _resolve_quantity(
        side=side,
        order_amount=safe_order_amount,
        reference_price=reference_price,
        portfolio_snapshot=portfolio_snapshot,
        stock_code=stock_code,
    )

    if quantity <= 0:
        return None

    return OrderRequest(
        stock_code=stock_code,
        side=side,
        quantity=quantity,
        order_type="market",
        limit_price=None,
        order_amount=safe_order_amount,
        reason=final_decision.reason_summary
        or final_decision.user_message
        or "Risk Guard 검증을 통과한 최종 투자 결정에 따라 주문 요청을 생성합니다.",
    )


def _resolve_reference_price(
    *,
    final_decision: FinalDecision,
    portfolio_snapshot: dict[str, Any],
    stock_code: str,
) -> int:
    """
    주문 수량 계산에 사용할 기준 가격을 찾는다.

    우선순위:
    1. portfolio_snapshot의 해당 종목 current_price
    2. final_decision.target_price

    현재 FinalDecision에는 current_price 필드가 없으므로,
    MVP에서는 target_price를 fallback 기준으로 사용한다.
    """

    position = _find_position(portfolio_snapshot, stock_code)

    current_price = get_value(position, "current_price")

    if current_price is not None:
        return _safe_to_int(current_price)

    if final_decision.target_price is not None:
        return _safe_to_int(final_decision.target_price)

    return 0


def _resolve_quantity(
    *,
    side: str,
    order_amount: int,
    reference_price: int,
    portfolio_snapshot: dict[str, Any],
    stock_code: str,
) -> int:
    """
    주문 수량을 계산한다.

    매수:
    - 주문 금액 / 기준 가격

    매도:
    - 주문 금액 / 기준 가격으로 계산하되,
    - 실제 보유 수량보다 많이 팔지 않도록 제한한다.
    """

    safe_order_amount = _safe_to_int(order_amount)
    safe_reference_price = _safe_to_int(reference_price)

    if safe_order_amount <= 0 or safe_reference_price <= 0:
        return 0

    calculated_quantity = safe_order_amount // safe_reference_price

    if calculated_quantity <= 0:
        return 0

    if side == "buy":
        return calculated_quantity

    holding_quantity = _get_holding_quantity(
        portfolio_snapshot=portfolio_snapshot,
        stock_code=stock_code,
    )

    if holding_quantity <= 0:
        return 0

    return min(calculated_quantity, holding_quantity)


def _find_position(
    portfolio_snapshot: dict[str, Any],
    stock_code: str,
) -> dict[str, Any] | None:
    """
    portfolio_snapshot에서 특정 종목 보유 정보를 찾는다.
    """

    positions = portfolio_snapshot.get("positions")

    if positions is None:
        positions = portfolio_snapshot.get("holdings", [])

    if not isinstance(positions, list):
        return None

    for position in positions:

        # 외부 snapshot 데이터는 비정상 값이 섞일 수 있으므로
        # dict 타입만 처리한다.
        if not isinstance(position, dict):
            continue

        position_code = (
            position.get("stock_code")
            or position.get("asset")
            or position.get("ticker")
        )

        if position_code == stock_code:
            return position

    return None


def _get_holding_quantity(
    *,
    portfolio_snapshot: dict[str, Any],
    stock_code: str,
) -> int:
    """
    특정 종목의 보유 수량을 가져온다.
    """

    position = _find_position(portfolio_snapshot, stock_code)

    if position is None:
        return 0

    quantity = position.get("quantity")

    if quantity is None:
        quantity = position.get("holding_quantity")

    if quantity is None:
        return 0

    return _safe_to_int(quantity)


def _safe_to_int(value: Any, default: int = 0) -> int:
    """
    다양한 입력 값을 안전하게 int로 변환한다.

    외부 snapshot 데이터는:
    - "1234"
    - "1234.5"
    - None
    - "N/A"
    - {}
    등 비정상 값이 들어올 수 있다.

    build_order_request는 주문 생성 단계이므로,
    예외를 발생시키기보다 안전하게 default 값으로 fallback한다.
    """

    if value is None:
        return default

    try:
        return int(float(value))
    except (TypeError, ValueError):
        return default