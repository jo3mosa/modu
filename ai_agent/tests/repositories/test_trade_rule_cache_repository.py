from app.repositories.trade_rule_cache_repository import MockTradeRuleCacheRepository


def test_set_and_get_trade_rule() -> None:
    """
    사용자별 자동매매 규칙을 저장하고 조회할 수 있어야 한다.
    """
    repository = MockTradeRuleCacheRepository()

    trade_rule = {
        "take_profit_rate": 10.0,
        "stop_loss_rate": -5.0,
    }

    repository.set_trade_rule(user_id=1, trade_rule=trade_rule)

    result = repository.get_trade_rule(user_id=1)

    assert result == trade_rule


def test_get_missing_trade_rule_returns_none() -> None:
    """
    저장되지 않은 사용자의 규칙을 조회하면 None을 반환해야 한다.
    """
    repository = MockTradeRuleCacheRepository()

    result = repository.get_trade_rule(user_id=999)

    assert result is None


def test_delete_trade_rule() -> None:
    """
    저장된 자동매매 규칙을 삭제할 수 있어야 한다.
    """
    repository = MockTradeRuleCacheRepository()

    repository.set_trade_rule(user_id=1, trade_rule={"take_profit_rate": 10.0})
    repository.delete_trade_rule(user_id=1)

    result = repository.get_trade_rule(user_id=1)

    assert result is None


def test_delete_non_existing_trade_rule_does_not_raise() -> None:
    """
    존재하지 않는 규칙을 삭제해도 예외가 발생하지 않아야 한다.
    """
    repository = MockTradeRuleCacheRepository()

    repository.delete_trade_rule(user_id=999)


def test_overwrite_trade_rule() -> None:
    """
    같은 사용자에게 규칙을 다시 저장하면 덮어써야 한다.
    """
    repository = MockTradeRuleCacheRepository()

    repository.set_trade_rule(user_id=1, trade_rule={"take_profit_rate": 10.0})
    repository.set_trade_rule(user_id=1, trade_rule={"take_profit_rate": 15.0, "stop_loss_rate": -3.0})

    result = repository.get_trade_rule(user_id=1)

    assert result == {"take_profit_rate": 15.0, "stop_loss_rate": -3.0}


def test_trade_rules_are_independent_per_user() -> None:
    """
    사용자별 규칙은 서로 독립적으로 저장되어야 한다.
    """
    repository = MockTradeRuleCacheRepository()

    repository.set_trade_rule(user_id=1, trade_rule={"take_profit_rate": 10.0})
    repository.set_trade_rule(user_id=2, trade_rule={"take_profit_rate": 5.0})

    assert repository.get_trade_rule(user_id=1) == {"take_profit_rate": 10.0}
    assert repository.get_trade_rule(user_id=2) == {"take_profit_rate": 5.0}


def test_get_trade_rule_returns_deep_copy() -> None:
    """
    조회한 규칙을 수정해도 저장된 원본에 영향을 주지 않아야 한다.
    """
    repository = MockTradeRuleCacheRepository()

    repository.set_trade_rule(user_id=1, trade_rule={"take_profit_rate": 10.0})

    result = repository.get_trade_rule(user_id=1)
    result["take_profit_rate"] = 99.0

    original = repository.get_trade_rule(user_id=1)

    assert original["take_profit_rate"] == 10.0
