from typing import Any

from app.position_monitoring.schemas import PositionEvent


def create_position_trigger(
    event: PositionEvent,
) -> dict[str, Any]:
    """
    Position Event를 User-specific Trigger 형식으로 변환한다.

    Position Monitoring은 단순 이벤트 감지 역할만 수행하고,
    이후 Reasoning Layer / Order Pipeline 실행은
    Trigger 기반으로 분기한다.
    """

    return {
        "trigger_type": "POSITION_EVENT",

        "user_id": event.user_id,

        "stock_code": event.stock_code,

        "event_type": event.event_type,

        "timestamp": event.timestamp.isoformat(),

        "payload": {
            "current_price": event.current_price,

            "profit_rate": event.profit_rate,

            "trade_rule": event.trade_rule,

            "position": event.position,
        },
    }