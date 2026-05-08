import copy
import json
from json import JSONDecodeError
from typing import Any, Protocol

from app.config.redis import get_redis_client


class PositionCacheRepository(Protocol):
    """
    사용자별 보유 종목 상세 정보를 저장/조회하는 Repository 인터페이스.

    PositionIndexRepository가
    "이 종목을 누가 보유 중인가?"를 관리한다면,

    PositionCacheRepository는
    "이 사용자가 이 종목을 얼마에, 얼마나 보유 중인가?"를 관리한다.
    """

    def set_position(
        self,
        user_id: int,
        stock_code: str,
        position: dict[str, Any],
    ) -> None:
        ...

    def get_position(
        self,
        user_id: int,
        stock_code: str,
    ) -> dict[str, Any] | None:
        ...

    def delete_position(
        self,
        user_id: int,
        stock_code: str,
    ) -> None:
        ...


class RedisPositionCacheRepository:
    """
    Redis String(JSON)을 사용해 사용자별 보유 종목 상세 정보를 캐싱한다.

    Redis key 예시:
        position:user:1:stock:005930

    Redis value 예시:
        {
            "stock_code": "005930",
            "quantity": 10,
            "average_price": 70000,
            "evaluation_amount": 760000
        }
    """

    KEY_PREFIX = "position:user"

    def __init__(self) -> None:
        self.redis_client = get_redis_client()

    def _key(self, user_id: int, stock_code: str) -> str:
        """
        user_id와 stock_code를 Redis key 형식으로 변환한다.
        """
        return f"{self.KEY_PREFIX}:{user_id}:stock:{stock_code}"

    def set_position(
        self,
        user_id: int,
        stock_code: str,
        position: dict[str, Any],
    ) -> None:
        """
        사용자별 보유 종목 상세 정보를 Redis에 저장한다.

        이 Repository는 포지션 상세 정보만 저장한다.
        종목별 보유 사용자 index 추가/삭제는 PositionIndexRepository가 담당한다.
        """
        self.redis_client.set(
            self._key(user_id, stock_code),
            json.dumps(position, ensure_ascii=False),
        )

    def get_position(
        self,
        user_id: int,
        stock_code: str,
    ) -> dict[str, Any] | None:
        """
        사용자별 보유 종목 상세 정보를 조회한다.
        """
        raw_value = self.redis_client.get(self._key(user_id, stock_code))

        if raw_value is None:
            return None

        try:
            return json.loads(raw_value)
        except (JSONDecodeError, UnicodeDecodeError, TypeError,):
            return None

    def delete_position(
        self,
        user_id: int,
        stock_code: str,
    ) -> None:
        """
        사용자별 보유 종목 상세 정보를 Redis에서 삭제한다.
        """
        self.redis_client.delete(self._key(user_id, stock_code))


class MockPositionCacheRepository:
    """
    Redis 없이 테스트하거나 로컬에서 대체 사용할 수 있는 Mock Repository.
    """

    def __init__(self) -> None:
        self._store: dict[tuple[int, str], dict[str, Any]] = {}

    def set_position(
        self,
        user_id: int,
        stock_code: str,
        position: dict[str, Any],
    ) -> None:
        """
        메모리 dict에 사용자별 보유 종목 상세 정보를 저장한다.
        """
        self._store[(user_id, stock_code)] = copy.deepcopy(position)

    def get_position(
        self,
        user_id: int,
        stock_code: str,
    ) -> dict[str, Any] | None:
        """
        저장된 사용자별 보유 종목 상세 정보를 조회한다.
        """
        position = self._store.get((user_id, stock_code))

        if position is None:
            return None

        return copy.deepcopy(position)

    def delete_position(
        self,
        user_id: int,
        stock_code: str,
    ) -> None:
        """
        저장된 사용자별 보유 종목 상세 정보를 삭제한다.
        """
        self._store.pop((user_id, stock_code), None)