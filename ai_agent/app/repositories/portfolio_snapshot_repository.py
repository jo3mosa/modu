import json
import logging
from typing import Protocol

from app.config.redis import get_redis_client

logger = logging.getLogger(__name__)


class PortfolioSnapshotRepository(Protocol):
    """
    user_id 기준으로 포트폴리오 스냅샷을 조회하는 Repository 인터페이스.
    """

    def get(self, user_id: int) -> dict:
        ...


class RedisPortfolioSnapshotRepository:
    """
    Redis String(JSON)을 사용해 사용자별 포트폴리오 스냅샷을 조회한다.

    Redis key 예시:
        portfolio:snapshot:1

    Redis value 예시:
        {"cash_balance": 1000000, "total_assets": 5000000, "holdings": [...]}
    """

    KEY_PREFIX = "portfolio:snapshot"

    def __init__(self) -> None:
        self.redis_client = get_redis_client()

    def _key(self, user_id: int) -> str:
        return f"{self.KEY_PREFIX}:{user_id}"

    def get(self, user_id: int) -> dict:
        try:
            raw = self.redis_client.get(self._key(user_id))

            if raw is None:
                return {}

            parsed = json.loads(raw)
        except Exception:
            logger.exception("Redis 포트폴리오 스냅샷 조회/파싱 실패: user_id=%s", user_id)
            raise

        if not isinstance(parsed, dict):
            logger.error(
                "포트폴리오 스냅샷이 dict가 아님: user_id=%s, type=%s",
                user_id,
                type(parsed).__name__,
            )
            return {}

        # BE 적재 키(holdings) → AI 내부 키(positions) 정규화.
        # BE 스펙: {"cash_balance", "total_assets", "holdings": [...]}
        if "holdings" in parsed and "positions" not in parsed:
            parsed["positions"] = parsed.pop("holdings")

        return parsed


class MockPortfolioSnapshotRepository:
    """
    테스트 환경에서 사용할 Mock Repository.
    """

    def __init__(self, store: dict[int, dict] | None = None) -> None:
        self._store: dict[int, dict] = store or {}

    def get(self, user_id: int) -> dict:
        return self._store.get(user_id, {})
