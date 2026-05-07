from datetime import datetime

from app.position_monitoring.schemas import PositionEvent
from app.triggers.position_trigger_factory import (
    create_position_trigger,
)


def test_create_position_trigger() -> None:
    """
    PositionEvent가 User-specific Trigger 형식으로
    정상 변환되는지 검증한다.
    """

    event = PositionEvent(
        user_id=1,
        stock_code="005930",
        event_type="TAKE_PROFIT_RATE_HIT",
        current_price=11000,
        profit_rate=10.0,
        trade_rule={
            "take_profit_rate": 10.0,
            "stop_loss_rate": -5.0,
        },
        position={
            "average_price": 10000,
            "quantity": 10,
        },
        timestamp=datetime.now(),
    )

    trigger = create_position_trigger(event)

    assert trigger["trigger_type"] == "POSITION_EVENT"

    assert trigger["user_id"] == 1

    assert trigger["stock_code"] == "005930"

    assert (
        trigger["event_type"]
        == "TAKE_PROFIT_RATE_HIT"
    )

    assert (
        trigger["payload"]["profit_rate"]
        == 10.0
    )