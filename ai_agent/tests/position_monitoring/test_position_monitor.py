from datetime import datetime

from app.position_monitoring.monitor import PositionMonitor
from app.position_monitoring.schemas import PriceTick

from app.repositories.position_cache_repository import (
    MockPositionCacheRepository,
)
from app.repositories.position_event_cooldown_repository import (
    MockPositionEventCooldownRepository,
)
from app.repositories.position_index_repository import (
    MockPositionIndexRepository,
)
from app.repositories.trade_rule_cache_repository import (
    MockTradeRuleCacheRepository,
)


def create_monitor() -> PositionMonitor:
    """
    테스트용 PositionMonitor 생성 헬퍼.
    """

    position_index_repository = (
        MockPositionIndexRepository()
    )

    position_cache_repository = (
        MockPositionCacheRepository()
    )

    trade_rule_repository = (
        MockTradeRuleCacheRepository()
    )

    cooldown_repository = (
        MockPositionEventCooldownRepository()
    )

    monitor = PositionMonitor(
        position_index_repository=position_index_repository,
        position_cache_repository=position_cache_repository,
        trade_rule_repository=trade_rule_repository,
        cooldown_repository=cooldown_repository,
    )

    return monitor


def test_take_profit_event_created() -> None:
    """
    수익률이 익절 기준 이상이면
    TAKE_PROFIT_RATE_HIT 이벤트가 생성되어야 한다.
    """

    position_index_repository = (
        MockPositionIndexRepository()
    )

    position_cache_repository = (
        MockPositionCacheRepository()
    )

    trade_rule_repository = (
        MockTradeRuleCacheRepository()
    )

    cooldown_repository = (
        MockPositionEventCooldownRepository()
    )

    monitor = PositionMonitor(
        position_index_repository=position_index_repository,
        position_cache_repository=position_cache_repository,
        trade_rule_repository=trade_rule_repository,
        cooldown_repository=cooldown_repository,
    )

    position_index_repository.add_user(
        stock_code="005930",
        user_id=1,
    )

    position_cache_repository.set_position(
        user_id=1,
        stock_code="005930",
        position={
            "average_price": 10000,
            "quantity": 10,
        },
    )

    trade_rule_repository.set_trade_rule(
        user_id=1,
        trade_rule={
            "take_profit_rate": 10.0,
            "stop_loss_rate": -5.0,
        },
    )

    tick = PriceTick(
        stock_code="005930",
        current_price=11100,
        timestamp=datetime.now(),
    )

    events = monitor.detect_events(tick)

    assert len(events) == 1

    assert (
        events[0].event_type
        == "TAKE_PROFIT_RATE_HIT"
    )


def test_stop_loss_event_created() -> None:
    """
    수익률이 손절 기준 이하이면
    STOP_LOSS_RATE_HIT 이벤트가 생성되어야 한다.
    """

    position_index_repository = (
        MockPositionIndexRepository()
    )

    position_cache_repository = (
        MockPositionCacheRepository()
    )

    trade_rule_repository = (
        MockTradeRuleCacheRepository()
    )

    cooldown_repository = (
        MockPositionEventCooldownRepository()
    )

    monitor = PositionMonitor(
        position_index_repository=position_index_repository,
        position_cache_repository=position_cache_repository,
        trade_rule_repository=trade_rule_repository,
        cooldown_repository=cooldown_repository,
    )

    position_index_repository.add_user(
        stock_code="005930",
        user_id=1,
    )

    position_cache_repository.set_position(
        user_id=1,
        stock_code="005930",
        position={
            "average_price": 10000,
            "quantity": 10,
        },
    )

    trade_rule_repository.set_trade_rule(
        user_id=1,
        trade_rule={
            "take_profit_rate": 10.0,
            "stop_loss_rate": -5.0,
        },
    )

    tick = PriceTick(
        stock_code="005930",
        current_price=9400,
        timestamp=datetime.now(),
    )

    events = monitor.detect_events(tick)

    assert len(events) == 1

    assert (
        events[0].event_type
        == "STOP_LOSS_RATE_HIT"
    )


def test_no_event_when_no_position_user() -> None:
    """
    해당 종목 보유 사용자가 없으면
    이벤트가 생성되지 않아야 한다.
    """

    monitor = create_monitor()

    tick = PriceTick(
        stock_code="005930",
        current_price=10000,
        timestamp=datetime.now(),
    )

    events = monitor.detect_events(tick)

    assert events == []


def test_no_event_when_trade_rule_missing() -> None:
    """
    trade_rule이 없으면
    이벤트가 생성되지 않아야 한다.
    """

    position_index_repository = (
        MockPositionIndexRepository()
    )

    position_cache_repository = (
        MockPositionCacheRepository()
    )

    trade_rule_repository = (
        MockTradeRuleCacheRepository()
    )

    cooldown_repository = (
        MockPositionEventCooldownRepository()
    )

    monitor = PositionMonitor(
        position_index_repository=position_index_repository,
        position_cache_repository=position_cache_repository,
        trade_rule_repository=trade_rule_repository,
        cooldown_repository=cooldown_repository,
    )

    position_index_repository.add_user(
        stock_code="005930",
        user_id=1,
    )

    position_cache_repository.set_position(
        user_id=1,
        stock_code="005930",
        position={
            "average_price": 10000,
        },
    )

    tick = PriceTick(
        stock_code="005930",
        current_price=12000,
        timestamp=datetime.now(),
    )

    events = monitor.detect_events(tick)

    assert events == []


def test_cooldown_blocks_duplicate_event() -> None:
    """
    cooldown 활성화 상태에서는
    동일 이벤트가 중복 생성되지 않아야 한다.
    """

    position_index_repository = (
        MockPositionIndexRepository()
    )

    position_cache_repository = (
        MockPositionCacheRepository()
    )

    trade_rule_repository = (
        MockTradeRuleCacheRepository()
    )

    cooldown_repository = (
        MockPositionEventCooldownRepository()
    )

    monitor = PositionMonitor(
        position_index_repository=position_index_repository,
        position_cache_repository=position_cache_repository,
        trade_rule_repository=trade_rule_repository,
        cooldown_repository=cooldown_repository,
    )

    position_index_repository.add_user(
        stock_code="005930",
        user_id=1,
    )

    position_cache_repository.set_position(
        user_id=1,
        stock_code="005930",
        position={
            "average_price": 10000,
        },
    )

    trade_rule_repository.set_trade_rule(
        user_id=1,
        trade_rule={
            "take_profit_rate": 10.0,
            "stop_loss_rate": -5.0,
        },
    )

    tick = PriceTick(
        stock_code="005930",
        current_price=11100,
        timestamp=datetime.now(),
    )

    first_events = monitor.detect_events(tick)

    second_events = monitor.detect_events(tick)

    assert len(first_events) == 1

    assert second_events == []