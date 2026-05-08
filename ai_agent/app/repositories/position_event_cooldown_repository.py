from typing import Protocol

from app.config.redis import get_redis_client


class PositionEventCooldownRepository(Protocol):
    """
    Position Event 중복 발생 방지를 위한 cooldown Repository 인터페이스.

    같은 사용자/종목/이벤트 타입 조합에 대해
    일정 시간 동안 이벤트 재발생을 막는다.
    """

    def is_cooldown_active(
        self,
        user_id: int,
        stock_code: str,
        event_type: str,
    ) -> bool:
        ...

    def activate_cooldown(
        self,
        user_id: int,
        stock_code: str,
        event_type: str,
        ttl_seconds: int,
    ) -> None:
        ...


class RedisPositionEventCooldownRepository:
    """
    Redis TTL을 사용해 Position Event cooldown을 관리한다.

    Redis key 예시:
        position:event:cooldown:1:005930:TAKE_PROFIT_RATE_HIT
    """

    KEY_PREFIX = "position:event:cooldown"

    def __init__(self) -> None:
        self.redis_client = get_redis_client()

    def _key(
        self,
        user_id: int,
        stock_code: str,
        event_type: str,
    ) -> str:
        """
        cooldown Redis key를 생성한다.
        """
        return (
            f"{self.KEY_PREFIX}:"
            f"{user_id}:{stock_code}:{event_type}"
        )

    def is_cooldown_active(
        self,
        user_id: int,
        stock_code: str,
        event_type: str,
    ) -> bool:
        """
        현재 cooldown이 활성화 상태인지 확인한다.
        """
        return self.redis_client.exists(
            self._key(user_id, stock_code, event_type)
        ) > 0

    def activate_cooldown(
        self,
        user_id: int,
        stock_code: str,
        event_type: str,
        ttl_seconds: int,
    ) -> None:
        """
        cooldown을 활성화한다.

        TTL이 만료되면 자동으로 cooldown이 해제된다.
        """
        self.redis_client.set(
            self._key(user_id, stock_code, event_type),
            "1",
            ex=ttl_seconds,
        )


class MockPositionEventCooldownRepository:
    """
    테스트 환경용 Mock cooldown Repository.
    """

    def __init__(self) -> None:
        self._store: set[str] = set()

    def _key(
        self,
        user_id: int,
        stock_code: str,
        event_type: str,
    ) -> str:
        return (
            f"{user_id}:{stock_code}:{event_type}"
        )

    def is_cooldown_active(
        self,
        user_id: int,
        stock_code: str,
        event_type: str,
    ) -> bool:
        return (
            self._key(user_id, stock_code, event_type)
            in self._store
        )

    def activate_cooldown(
        self,
        user_id: int,
        stock_code: str,
        event_type: str,
        ttl_seconds: int,
    ) -> None:
        """
        Mock에서는 TTL 없이 단순 저장만 수행한다.
        """
        self._store.add(
            self._key(user_id, stock_code, event_type)
        )