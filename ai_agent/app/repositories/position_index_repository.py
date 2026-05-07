from typing import Protocol

from app.config.redis import get_redis_client


class PositionIndexRepository(Protocol):
    """
    stock_code 기준으로 해당 종목을 보유한 user_id 목록을 관리하는 Repository 인터페이스.

    실제 Redis 구현체와 Mock 구현체가 같은 메서드 이름을 갖도록 맞추기 위한 용도.
    """

    def add_user(self, stock_code: str, user_id: int) -> None:
        ...

    def get_user_ids_by_stock(self, stock_code: str) -> list[int]:
        ...

    def remove_user(self, stock_code: str, user_id: int) -> None:
        ...


class RedisPositionIndexRepository:
    """
    Redis Set을 사용해 종목별 보유 사용자 인덱스를 관리한다.

    Redis key 예시:
        position:index:stock:005930

    Redis value 예시:
        Set(1, 2, 3)
    """

    KEY_PREFIX = "position:index:stock"

    def __init__(self) -> None:
        """
        Repository 생성 시 Redis client를 주입받는다.

        매번 새 연결을 만드는 것이 아니라 같은 client를 재사용한다.
        """
        self.redis_client = get_redis_client()

    def _key(self, stock_code: str) -> str:
        """
        stock_code를 Redis key 형식으로 변환한다.
        """
        return f"{self.KEY_PREFIX}:{stock_code}"

    def add_user(self, stock_code: str, user_id: int) -> None:
        """
        특정 종목을 보유한 사용자 목록에 user_id를 추가한다.
        """
        self.redis_client.sadd(self._key(stock_code), user_id)

    def get_user_ids_by_stock(self, stock_code: str) -> list[int]:
        """
        특정 stock_code를 보유한 user_id 목록을 조회한다.
        """
        user_ids = self.redis_client.smembers(self._key(stock_code))
        return sorted(int(user_id) for user_id in user_ids)

    def remove_user(self, stock_code: str, user_id: int) -> None:
        """
        특정 종목의 보유 사용자 목록에서 user_id를 제거한다.
        """
        self.redis_client.srem(self._key(stock_code), user_id)


class MockPositionIndexRepository:
    """
    테스트 환경에서 사용할 Mock Repository.
    """

    def __init__(self) -> None:
        self._store: dict[str, set[int]] = {}

    def add_user(self, stock_code: str, user_id: int) -> None:
        """
        메모리 dict + set 구조에 user_id를 추가한다.
        """
        self._store.setdefault(stock_code, set()).add(user_id)

    def get_user_ids_by_stock(self, stock_code: str) -> list[int]:
        """
        특정 stock_code에 해당하는 user_id 목록을 반환한다.

        없는 stock_code면 빈 리스트를 반환한다.
        """
        return sorted(self._store.get(stock_code, set()))

    def remove_user(self, stock_code: str, user_id: int) -> None:
        """
        특정 stock_code의 user_id를 제거한다.

        discard를 사용하면 user_id가 없어도 예외가 발생하지 않는다.
        """
        if stock_code not in self._store:
            return

        self._store[stock_code].discard(user_id)

        if not self._store[stock_code]:
            del self._store[stock_code]