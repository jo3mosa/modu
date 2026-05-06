from pprint import pprint

from app.triggers.schemas import MarketTriggerEvent
from app.triggers.user_trigger_matcher import (
    match_market_event_to_users,
)


def main() -> None:
    """
    Mock MarketTriggerEvent를 생성하고
    UserTriggerEvent list가 정상 생성되는지 확인한다.
    """

    mock_market_event = MarketTriggerEvent(
        event_id="market_event_001",
        trigger_type="MARKET_EVENT",
        trigger_reason="RSI 과열 및 긍정 공시 감지",
        stock_code="005930",
        market_snapshot={
            "market_status": "OPEN",
        },
        analysis_snapshot={
            "stock_code": "005930",
            "signals": {
                "technical": {
                    "momentum": {
                        "rsi_14": 82.5,
                    }
                }
            },
        },
        candidate_assets=[
            {
                "stock_code": "005930",
                "stock_name": "삼성전자",
            }
        ],
    )

    user_trigger_events = match_market_event_to_users(
        mock_market_event
    )

    print("=" * 60)
    print("생성된 UserTriggerEvent 개수")
    print(len(user_trigger_events))
    print("=" * 60)

    for event in user_trigger_events:
        pprint(event.model_dump())
        print("-" * 60)


if __name__ == "__main__":
    main()