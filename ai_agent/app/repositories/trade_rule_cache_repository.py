import copy
import json
from json import JSONDecodeError
from typing import Any, Protocol

from app.config.redis import get_redis_client


class TradeRuleCacheRepository(Protocol):
    """
    사용자별 자동매매 규칙을 저장/조회하는 Repository 인터페이스.

    이 규칙은 특정 종목에만 적용되는 가격 기준이 아니라,
    사용자의 모든 보유 포지션에 일괄 적용되는 수익률 기준이다.
    """

    def set_trade_rule(
        self,
        user_id: int,
        trade_rule: dict[str, Any],
    ) -> None:
        ...

    def get_trade_rule(
        self,
        user_id: int,
    ) -> dict[str, Any] | None:
        ...

    def delete_trade_rule(
        self,
        user_id: int,
    ) -> None:
        ...


class RedisTradeRuleCacheRepository:
    """
    Redis String(JSON)을 사용해 사용자별 자동매매 규칙을 캐싱한다.

    Redis key 예시:
        trade_rule:user:1

    Redis value 예시:
        {
            "take_profit_rate": 10.0,
            "stop_loss_rate": -5.0,
            "profit_rate_spike_threshold": 3.0
        }
    """

    KEY_PREFIX = "trade_rule:user"

    def __init__(self) -> None:
        self.redis_client = get_redis_client()

    def _key(self, user_id: int) -> str:
        """
        user_id를 Redis key 형식으로 변환한다.
        """
        return f"{self.KEY_PREFIX}:{user_id}"

    def set_trade_rule(
        self,
        user_id: int,
        trade_rule: dict[str, Any],
    ) -> None:
        """
        사용자별 자동매매 규칙을 Redis에 저장한다.
        """
        self.redis_client.set(
            self._key(user_id),
            json.dumps(trade_rule, ensure_ascii=False),
        )

    def get_trade_rule(
        self,
        user_id: int,
    ) -> dict[str, Any] | None:
        """
        사용자별 자동매매 규칙을 조회한다.
        """
        raw_value = self.redis_client.get(self._key(user_id))

        if raw_value is None:
            return None

        try:
            return json.loads(raw_value)
        except (JSONDecodeError, UnicodeDecodeError, TypeError,):
            return None

    def delete_trade_rule(
        self,
        user_id: int,
    ) -> None:
        """
        사용자별 자동매매 규칙 캐시를 삭제한다.
        """
        self.redis_client.delete(self._key(user_id))


class MockTradeRuleCacheRepository:
    """
    Redis 없이 테스트하거나 로컬에서 대체 사용할 수 있는 Mock Repository.
    """

    def __init__(self) -> None:
        self._store: dict[int, dict[str, Any]] = {}

    def set_trade_rule(
        self,
        user_id: int,
        trade_rule: dict[str, Any],
    ) -> None:
        """
        메모리 dict에 사용자별 자동매매 규칙을 저장한다.
        """
        self._store[user_id] = copy.deepcopy(trade_rule)

    def get_trade_rule(
        self,
        user_id: int,
    ) -> dict[str, Any] | None:
        """
        저장된 사용자별 자동매매 규칙을 조회한다.
        """
        trade_rule = self._store.get(user_id)

        if trade_rule is None:
            return None

        return copy.deepcopy(trade_rule)

    def delete_trade_rule(
        self,
        user_id: int,
    ) -> None:
        """
        저장된 사용자별 자동매매 규칙을 삭제한다.
        """
        self._store.pop(user_id, None)