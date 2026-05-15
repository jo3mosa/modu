from datetime import datetime, timezone
from pprint import pprint

from app.triggers.schemas import MarketTrigger, MarketTriggerEvent
from app.triggers.user_trigger_matcher import match_market_event_to_users
from app.triggers.state_factory import build_state_from_user_trigger
from app.repositories.position_index_repository import MockPositionIndexRepository
from app.repositories.portfolio_snapshot_repository import MockPortfolioSnapshotRepository
from app.repositories.market_price_repository import MockMarketPriceRepository


def _make_event(stock_code: str = "005930") -> MarketTriggerEvent:
    return MarketTriggerEvent(
        stock_code=stock_code,
        timestamp=datetime.now(tz=timezone.utc),
        trigger=MarketTrigger(
            rule_ids=["RSI-002"],
            trigger_reason=["RSI 과매수"],
        ),
        analysis_snapshot={"technical": {"momentum": {"rsi_14": 82.5}}},
    )


def _make_repos(
    user_ids: list[int] | None = None,
    stock_code: str = "005930",
    current_price: int | None = 71000,
) -> tuple[MockPositionIndexRepository, MockPortfolioSnapshotRepository, MockMarketPriceRepository]:
    position_repo = MockPositionIndexRepository()
    for uid in ([1, 2] if user_ids is None else user_ids):
        position_repo.add_user(stock_code, uid)

    portfolio_repo = MockPortfolioSnapshotRepository(
        store={
            1: {
                "cash_balance": 1_000_000,
                "total_assets": 5_000_000,
                "holdings": [{"stock_code": stock_code, "stock_name": "삼성전자", "quantity": 10, "average_price": 75000}],
            },
            2: {
                "cash_balance": 500_000,
                "total_assets": 2_000_000,
                "holdings": [{"stock_code": stock_code, "stock_name": "삼성전자", "quantity": 5, "average_price": 72000}],
            },
        }
    )

    price_store = {stock_code: current_price} if current_price is not None else {}
    price_repo = MockMarketPriceRepository(store=price_store)

    return position_repo, portfolio_repo, price_repo


def test_match_returns_event_per_holding_user() -> None:
    """보유 사용자 수만큼 UserTriggerEvent가 생성된다."""
    pos, port, price = _make_repos(user_ids=[1, 2])
    events = match_market_event_to_users(_make_event(), pos, port, price)
    assert len(events) == 2


def test_match_assigns_correct_user_ids() -> None:
    """생성된 이벤트의 user_id가 보유 사용자와 일치한다."""
    pos, port, price = _make_repos(user_ids=[1, 2])
    events = match_market_event_to_users(_make_event(), pos, port, price)
    assert {e.user_id for e in events} == {1, 2}


def test_current_price_always_included_in_portfolio_snapshot() -> None:
    """current_price 조회 성공 시 portfolio_snapshot에 포함된다."""
    pos, port, price = _make_repos(current_price=71000)
    events = match_market_event_to_users(_make_event(), pos, port, price)
    for event in events:
        assert "current_price" in event.portfolio_snapshot
        assert event.portfolio_snapshot["current_price"] == 71000


def test_current_price_is_none_when_not_in_redis() -> None:
    """current_price 조회 실패(None) 시에도 portfolio_snapshot 키는 항상 존재한다."""
    pos, port, price = _make_repos(current_price=None)
    events = match_market_event_to_users(_make_event(), pos, port, price)
    for event in events:
        assert "current_price" in event.portfolio_snapshot
        assert event.portfolio_snapshot["current_price"] is None


def test_returns_empty_list_when_no_holders() -> None:
    """보유 사용자가 없으면 빈 리스트를 반환한다."""
    pos, port, price = _make_repos(user_ids=[])
    events = match_market_event_to_users(_make_event(), pos, port, price)
    assert events == []


def test_state_factory_converts_event_successfully() -> None:
    """UserTriggerEvent가 InvestmentAgentState로 정상 변환된다."""
    pos, port, price = _make_repos(user_ids=[1])
    events = match_market_event_to_users(_make_event(), pos, port, price)
    assert len(events) == 1
    state = build_state_from_user_trigger(events[0])
    assert state.user_id == 1
    assert state.portfolio_snapshot["current_price"] == 71000


def main() -> None:
    """수동 실행용 — 전체 흐름 출력 확인."""
    pos, port, price = _make_repos()
    events = match_market_event_to_users(_make_event(), pos, port, price)

    print("=" * 60)
    print(f"생성된 UserTriggerEvent 개수: {len(events)}")
    print("=" * 60)

    for event in events:
        pprint(event.model_dump())
        print("-" * 60)
        state = build_state_from_user_trigger(event)
        print("InvestmentAgentState 변환 성공")
        pprint(state.model_dump())
        print("=" * 60)


if __name__ == "__main__":
    main()
