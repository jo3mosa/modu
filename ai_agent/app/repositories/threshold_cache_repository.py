import copy
import json
from json import JSONDecodeError
from typing import Any, Protocol

from app.config.redis import get_redis_client


class ThresholdCacheRepository(Protocol):
    """
    사용자별 종목 threshold를 저장/조회하는 Repository 인터페이스.

    Redis 구현체와 Mock 구현체가 같은 메서드 구조를 갖도록 맞춘다.
    """

    def set_threshold(
        self,
        user_id: int,
        stock_code: str,
        threshold: dict[str, Any],
    ) -> None:
        ...

    def get_threshold(
        self,
        user_id: int,
        stock_code: str,
    ) -> dict[str, Any] | None:
        ...

    def delete_threshold(
        self,
        user_id: int,
        stock_code: str,
    ) -> None:
        ...


class RedisThresholdCacheRepository:
    """
    Redis String(JSON)을 사용해 사용자별 종목 threshold를 캐싱한다.

    Redis key 예시:
        threshold:user:1:stock:005930

    Redis value 예시:
        {
            "target_price": 85000,
            "stop_loss_price": 70000,
            "profit_rate_alert": 0.05,
            "loss_rate_alert": -0.03
        }
    """

    KEY_PREFIX = "threshold:user"

    def __init__(self) -> None:
        self.redis_client = get_redis_client()

    def _key(self, user_id: int, stock_code: str) -> str:
        """
        user_id와 stock_code를 Redis key 형식으로 변환한다.
        """
        return f"{self.KEY_PREFIX}:{user_id}:stock:{stock_code}"

    def set_threshold(
        self,
        user_id: int,
        stock_code: str,
        threshold: dict[str, Any],
    ) -> None:
        """
        사용자별 종목 threshold를 Redis에 저장한다.
        """
        self.redis_client.set(
            self._key(user_id, stock_code),
            json.dumps(threshold, ensure_ascii=False),
        )

    def get_threshold(
        self,
        user_id: int,
        stock_code: str,
    ) -> dict[str, Any] | None:
        """
        사용자별 종목 threshold를 조회한다.
        """
        raw_value = self.redis_client.get(self._key(user_id, stock_code))

        if raw_value is None:
            return None

        try:
            return json.loads(raw_value)
        except JSONDecodeError:
            return None


    def delete_threshold(
        self,
        user_id: int,
        stock_code: str,
    ) -> None:
        """
        사용자별 종목 threshold 캐시를 삭제한다.
        """
        self.redis_client.delete(self._key(user_id, stock_code))


class MockThresholdCacheRepository:
    """
    Redis 없이 테스트하거나 로컬에서 대체 사용할 수 있는 Mock Repository.

    실제 Redis 구현체와 동일한 메서드 이름을 제공한다.
    """

    def __init__(self) -> None:
        self._store: dict[tuple[int, str], dict[str, Any]] = {}

    def set_threshold(
        self,
        user_id: int,
        stock_code: str,
        threshold: dict[str, Any],
    ) -> None:
        """
        메모리 dict에 threshold를 저장한다.
        """
        self._store[(user_id, stock_code)] = copy.deepcopy(threshold)

    def get_threshold(
        self,
        user_id: int,
        stock_code: str,
    ) -> dict[str, Any] | None:
        """
        저장된 threshold를 조회한다.

        없으면 None을 반환한다.
        """
        threshold = self._store.get((user_id, stock_code))

        if threshold is None:
            return None

        return copy.deepcopy(threshold)

    def delete_threshold(
        self,
        user_id: int,
        stock_code: str,
    ) -> None:
        """
        저장된 threshold를 삭제한다.

        pop(..., None)을 사용해 값이 없어도 예외가 나지 않게 한다.
        """
        self._store.pop((user_id, stock_code), None)


def is_price_near_threshold(
    current_price: int | float,
    threshold: dict[str, Any],
    tolerance_rate: float = 0.01,
) -> bool:
    """
    현재가가 목표가 또는 손절가 근처인지 판단한다.

    tolerance_rate=0.01이면 기준가의 ±1% 이내를 '근처'로 본다.

    예시:
        target_price = 85000
        tolerance_rate = 0.01

        허용 범위:
            84150 <= current_price <= 85850
    """

    if not isinstance(current_price, (int, float)):
        return False

    target_price = threshold.get("target_price")
    stop_loss_price = threshold.get("stop_loss_price")

    prices_to_check = [
        price
        for price in [target_price, stop_loss_price]
        if isinstance(price, (int, float))
    ]

    for base_price in prices_to_check:
        lower_bound = base_price * (1 - tolerance_rate)
        upper_bound = base_price * (1 + tolerance_rate)

        if lower_bound <= current_price <= upper_bound:
            return True

    return False