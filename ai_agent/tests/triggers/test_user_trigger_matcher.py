from datetime import datetime, timezone
from pprint import pprint

from app.triggers.schemas import MarketTrigger, MarketTriggerEvent
from app.triggers.user_trigger_matcher import (
    match_market_event_to_users,
)
from app.triggers.state_factory import build_state_from_user_trigger


def main() -> None:
    """
    Mock MarketTriggerEvent를 생성하고
    UserTriggerEvent list가 정상 생성되는지 확인한다.

    DA 명세 정합화: trigger.{rule_ids, trigger_reason} nested 구조 사용.
    """

    mock_market_event = MarketTriggerEvent(
        stock_code="005930",
        timestamp=datetime.now(tz=timezone.utc),
        trigger=MarketTrigger(
            rule_ids=["RSI-002"],
            trigger_reason=["RSI 과매수"],
        ),
        analysis_snapshot={
            "technical": {
                "momentum": {"rsi_14": 82.5},
            },
        },
    )

    user_trigger_events = match_market_event_to_users(mock_market_event)

    print("=" * 60)
    print("생성된 UserTriggerEvent 개수")
    print(len(user_trigger_events))
    print("=" * 60)

    for event in user_trigger_events:
        pprint(event.model_dump())
        print("-" * 60)

        state = build_state_from_user_trigger(event)

        print("InvestmentAgentState 변환 성공")
        pprint(state.model_dump())
        print("=" * 60)


if __name__ == "__main__":
    main()
