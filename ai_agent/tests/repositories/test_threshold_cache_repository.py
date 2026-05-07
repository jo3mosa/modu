from app.repositories.threshold_cache_repository import (
    MockThresholdCacheRepository,
    is_price_near_threshold,
)


def test_set_and_get_threshold() -> None:
    """
    사용자별 종목 threshold를 저장하고 조회할 수 있어야 한다.
    """
    repository = MockThresholdCacheRepository()

    threshold = {
        "target_price": 85000,
        "stop_loss_price": 70000,
        "profit_rate_alert": 0.05,
        "loss_rate_alert": -0.03,
    }

    repository.set_threshold(
        user_id=1,
        stock_code="005930",
        threshold=threshold,
    )

    result = repository.get_threshold(
        user_id=1,
        stock_code="005930",
    )

    assert result == threshold


def test_get_missing_threshold_returns_none() -> None:
    """
    저장되지 않은 threshold를 조회하면 None을 반환해야 한다.
    """
    repository = MockThresholdCacheRepository()

    result = repository.get_threshold(
        user_id=1,
        stock_code="005930",
    )

    assert result is None


def test_delete_threshold() -> None:
    """
    저장된 threshold를 삭제할 수 있어야 한다.
    """
    repository = MockThresholdCacheRepository()

    threshold = {
        "target_price": 85000,
        "stop_loss_price": 70000,
    }

    repository.set_threshold(1, "005930", threshold)
    repository.delete_threshold(1, "005930")

    result = repository.get_threshold(1, "005930")

    assert result is None


def test_is_price_near_target_price() -> None:
    """
    현재가가 목표가의 허용 범위 안에 있으면 True를 반환해야 한다.
    """
    threshold = {
        "target_price": 85000,
        "stop_loss_price": 70000,
    }

    result = is_price_near_threshold(
        current_price=84500,
        threshold=threshold,
        tolerance_rate=0.01,
    )

    assert result is True


def test_is_price_near_stop_loss_price() -> None:
    """
    현재가가 손절가의 허용 범위 안에 있으면 True를 반환해야 한다.
    """
    threshold = {
        "target_price": 85000,
        "stop_loss_price": 70000,
    }

    result = is_price_near_threshold(
        current_price=69500,
        threshold=threshold,
        tolerance_rate=0.01,
    )

    assert result is True


def test_is_price_not_near_threshold() -> None:
    """
    현재가가 목표가/손절가 어느 쪽에도 근접하지 않으면 False를 반환해야 한다.
    """
    threshold = {
        "target_price": 85000,
        "stop_loss_price": 70000,
    }

    result = is_price_near_threshold(
        current_price=78000,
        threshold=threshold,
        tolerance_rate=0.01,
    )

    assert result is False


def test_is_price_near_threshold_without_price_keys() -> None:
    """
    threshold에 목표가/손절가가 없으면 False를 반환해야 한다.
    """
    threshold = {
        "profit_rate_alert": 0.05,
        "loss_rate_alert": -0.03,
    }

    result = is_price_near_threshold(
        current_price=78000,
        threshold=threshold,
    )

    assert result is False