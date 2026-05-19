"""
Risk Tier 기반 비보유 유저 BUY 추천 매칭 테스트.

기존 보유자 매칭(test_user_trigger_matcher.py)과는 별도로,
비보유자 포함 / is_holder 플래그 / run_and_publish 필터링 동작을 검증한다.
"""

from datetime import datetime, timezone
from unittest.mock import MagicMock, patch

import pytest

from app.repositories.buy_candidate_repository import MockBuyCandidateRepository
from app.repositories.market_price_repository import MockMarketPriceRepository
from app.repositories.portfolio_snapshot_repository import MockPortfolioSnapshotRepository
from app.repositories.position_index_repository import MockPositionIndexRepository
from app.triggers.schemas import MarketTrigger, MarketTriggerEvent, UserTriggerEvent
from app.triggers.user_trigger_matcher import match_market_event_to_users


# ─────────────────────────────────────────────
# 픽스처
# ─────────────────────────────────────────────

def _make_event(stock_code: str = "005930") -> MarketTriggerEvent:
    return MarketTriggerEvent(
        stock_code=stock_code,
        timestamp=datetime.now(tz=timezone.utc),
        trigger=MarketTrigger(rule_ids=["RSI-002"], trigger_reason=["RSI 과매수"]),
        analysis_snapshot={"technical": {"momentum": {"rsi_14": 82.5}}},
    )


def _make_repos(
    holder_ids: list[int] | None = None,
    stock_code: str = "005930",
    current_price: int | None = 71000,
) -> tuple[MockPositionIndexRepository, MockPortfolioSnapshotRepository, MockMarketPriceRepository]:
    pos = MockPositionIndexRepository()
    for uid in (holder_ids if holder_ids is not None else [1, 2]):
        pos.add_user(stock_code, uid)

    port = MockPortfolioSnapshotRepository(
        store={uid: {"cash_balance": 1_000_000, "total_assets": 5_000_000, "holdings": []} for uid in range(1, 10)}
    )
    price = MockMarketPriceRepository(store={stock_code: current_price} if current_price is not None else {})
    return pos, port, price


def _make_buy_repo(
    stock_code: str = "005930",
    eligible_ids: list[int] | None = None,
) -> MockBuyCandidateRepository:
    return MockBuyCandidateRepository(
        store={stock_code: eligible_ids or []}
    )


# ─────────────────────────────────────────────
# 비보유 유저 포함 매칭
# ─────────────────────────────────────────────

def test_non_holders_included_when_buy_repo_provided() -> None:
    """buy_candidate_repository가 주입되면 비보유 유저도 이벤트가 생성된다."""
    pos, port, price = _make_repos(holder_ids=[1], stock_code="005930")
    buy_repo = _make_buy_repo(eligible_ids=[1, 3, 4])  # 보유자 1 포함, 비보유자 3·4

    events = match_market_event_to_users(_make_event(), pos, port, price, buy_repo)

    assert len(events) == 3  # 보유자 1 + 비보유자 2
    user_ids = {e.user_id for e in events}
    assert user_ids == {1, 3, 4}


def test_non_holders_excluded_when_buy_repo_not_provided() -> None:
    """buy_candidate_repository 미주입 시 기존 보유자만 이벤트가 생성된다."""
    pos, port, price = _make_repos(holder_ids=[1, 2])

    events = match_market_event_to_users(_make_event(), pos, port, price)

    assert len(events) == 2
    assert all(e.is_holder for e in events)


def test_returns_empty_when_no_holders_and_no_buy_repo() -> None:
    """보유자도 없고 buy_repo도 없으면 빈 리스트를 반환한다."""
    pos, port, price = _make_repos(holder_ids=[])

    events = match_market_event_to_users(_make_event(), pos, port, price)

    assert events == []


def test_returns_empty_when_no_holders_and_empty_buy_repo() -> None:
    """보유자도 없고 buy_repo에 적격 유저도 없으면 빈 리스트를 반환한다."""
    pos, port, price = _make_repos(holder_ids=[])
    buy_repo = _make_buy_repo(eligible_ids=[])

    events = match_market_event_to_users(_make_event(), pos, port, price, buy_repo)

    assert events == []


# ─────────────────────────────────────────────
# is_holder 플래그
# ─────────────────────────────────────────────

def test_holder_flag_is_true_for_holders() -> None:
    """보유 유저의 UserTriggerEvent는 is_holder=True다."""
    pos, port, price = _make_repos(holder_ids=[1, 2])
    buy_repo = _make_buy_repo(eligible_ids=[1, 2, 3])

    events = match_market_event_to_users(_make_event(), pos, port, price, buy_repo)

    holder_events = [e for e in events if e.user_id in {1, 2}]
    assert all(e.is_holder for e in holder_events)


def test_holder_flag_is_false_for_non_holders() -> None:
    """비보유 유저의 UserTriggerEvent는 is_holder=False다."""
    pos, port, price = _make_repos(holder_ids=[1])
    buy_repo = _make_buy_repo(eligible_ids=[1, 3, 4])

    events = match_market_event_to_users(_make_event(), pos, port, price, buy_repo)

    non_holder_events = [e for e in events if e.user_id in {3, 4}]
    assert all(not e.is_holder for e in non_holder_events)


def test_user_in_both_eligible_and_holder_treated_as_holder() -> None:
    """적격 유저 목록에 이미 보유자가 포함돼 있으면 보유자로 처리한다."""
    pos, port, price = _make_repos(holder_ids=[1, 2])
    buy_repo = _make_buy_repo(eligible_ids=[1, 3])  # 보유자 1이 적격 목록에도 있음

    events = match_market_event_to_users(_make_event(), pos, port, price, buy_repo)

    user_id_to_event = {e.user_id: e for e in events}
    assert user_id_to_event[1].is_holder is True   # 보유자로 유지
    assert user_id_to_event[3].is_holder is False  # 비보유자로 포함


# ─────────────────────────────────────────────
# run_and_publish 필터링
# ─────────────────────────────────────────────

def _make_user_trigger_event(user_id: int, is_holder: bool) -> UserTriggerEvent:
    return UserTriggerEvent(
        timestamp=datetime.now(tz=timezone.utc),
        user_id=user_id,
        stock_code="005930",
        is_holder=is_holder,
    )


def _make_final_decision(action: str, side: str | None = None):
    decision = MagicMock()
    decision.action = action
    decision.side = side
    return decision


@pytest.mark.parametrize("action,side,expected_publish", [
    ("trade", "buy", True),   # BUY → 발행
    ("trade", "sell", False), # SELL → 생략
    ("hold", None, False),    # HOLD → 생략
])
def test_run_and_publish_filters_non_holder_by_decision(action, side, expected_publish) -> None:
    """비보유자 이벤트는 BUY 결정일 때만 Kafka에 발행된다."""
    from app.graph.runner import run_and_publish

    event = _make_user_trigger_event(user_id=99, is_holder=False)
    mock_result = {"final_decision": _make_final_decision(action, side), "flow_status": "completed"}

    with patch("app.graph.runner.run_pipeline", return_value=mock_result), \
         patch("app.graph.runner.get_kafka_producer") as mock_producer_factory:

        mock_producer = MagicMock()
        mock_producer_factory.return_value = mock_producer

        run_and_publish(event)

        if expected_publish:
            mock_producer.send.assert_called_once()
        else:
            mock_producer.send.assert_not_called()


def test_run_and_publish_holder_always_publishes_regardless_of_decision() -> None:
    """보유자 이벤트는 SELL/HOLD 결정도 항상 Kafka에 발행된다."""
    from app.graph.runner import run_and_publish

    event = _make_user_trigger_event(user_id=1, is_holder=True)
    mock_result = {"final_decision": _make_final_decision("hold"), "flow_status": "hold"}

    with patch("app.graph.runner.run_pipeline", return_value=mock_result), \
         patch("app.graph.runner.get_kafka_producer") as mock_producer_factory:

        mock_producer = MagicMock()
        mock_producer_factory.return_value = mock_producer

        run_and_publish(event)

        mock_producer.send.assert_called_once()


# ─────────────────────────────────────────────
# is_holder Kafka 페이로드 포함 여부
# ─────────────────────────────────────────────

@pytest.mark.parametrize("is_holder", [True, False])
def test_is_holder_included_in_kafka_payload(is_holder: bool) -> None:
    """is_holder 값이 Kafka 페이로드에 포함된다."""
    from app.graph.runner import run_and_publish

    event = _make_user_trigger_event(user_id=1, is_holder=is_holder)
    mock_result = {"final_decision": _make_final_decision("trade", "buy"), "flow_status": "completed"}

    with patch("app.graph.runner.run_pipeline", return_value=mock_result), \
         patch("app.graph.runner.get_kafka_producer") as mock_producer_factory:

        mock_producer = MagicMock()
        mock_producer_factory.return_value = mock_producer

        run_and_publish(event)

        payload = mock_producer.send.call_args[1]["value"]
        assert "is_holder" in payload
        assert payload["is_holder"] is is_holder
