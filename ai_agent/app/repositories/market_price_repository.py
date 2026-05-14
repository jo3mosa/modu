import logging
from typing import Protocol

from app.config.redis import get_redis_client

logger = logging.getLogger(__name__)


class MarketPriceRepository(Protocol):
    """
    stock_code 기준으로 실시간 현재가를 조회하는 Repository 인터페이스.
    """

    def get(self, stock_code: str) -> int | None:
        ...


class RedisMarketPriceRepository:
    """
    Redis String을 사용해 종목별 실시간 현재가를 조회한다.

    Redis key 예시:
        market:price:005930

    Redis value 예시:
        "71000"
    """

    KEY_PREFIX = "market:price"

    def __init__(self) -> None:
        self.redis_client = get_redis_client()

    def _key(self, stock_code: str) -> str:
        return f"{self.KEY_PREFIX}:{stock_code}"

    def get(self, stock_code: str) -> int | None:
        try:
            raw = self.redis_client.get(self._key(stock_code))
        except Exception:
            logger.exception("Redis 조회 실패: stock_code=%s", stock_code)
            raise

        if raw is None:
            return None

        return int(raw)


class MockMarketPriceRepository:
    """
    테스트 환경에서 사용할 Mock Repository.
    """

    def __init__(self, store: dict[str, int] | None = None) -> None:
        self._store: dict[str, int] = store or {}

    def get(self, stock_code: str) -> int | None:
        return self._store.get(stock_code)
