import logging
from typing import Protocol

from app.config.redis import get_redis_client

logger = logging.getLogger(__name__)

_KEY_RISK_TIER = "stock:risk_tier"
_KEY_USERS_BY_GRADE = "users:by_grade"


class BuyCandidateRepository(Protocol):
    def get_eligible_user_ids(self, stock_code: str) -> list[int]:
        """stock_tier 이상의 risk_grade를 가진 전체 유저 ID 목록을 반환한다."""
        ...


class RedisBuyCandidateRepository:
    """
    Redis를 직접 읽어 매수 추천 적격 유저를 조회한다.

    사용 키:
        stock:risk_tier:{stock_code}   — DA 배치가 SET. 종목별 risk tier (1~5 string)
        users:by_grade:{1~5}           — 백엔드가 SADD/SREM. 등급별 유저 ID Set

    조회 로직:
        1. stock:risk_tier:{code} → tier T
        2. SUNION users:by_grade:{T} ~ users:by_grade:{5} → 적격 유저 전체
        3. tier 미설정이면 빈 리스트 반환 (DA 배치 미실행 or TTL 만료)
    """

    def __init__(self) -> None:
        self.redis_client = get_redis_client()

    def get_eligible_user_ids(self, stock_code: str) -> list[int]:
        try:
            tier_raw = self.redis_client.get(f"{_KEY_RISK_TIER}:{stock_code}")
        except Exception:
            logger.exception("stock:risk_tier 조회 실패: stock_code=%s", stock_code)
            return []

        if tier_raw is None:
            logger.info("stock:risk_tier 미설정 (DA 배치 미실행) - 비보유자 매칭 생략: stock_code=%s", stock_code)
            return []

        tier = int(tier_raw)
        grade_keys = [f"{_KEY_USERS_BY_GRADE}:{t}" for t in range(tier, 6)]

        try:
            raw_ids = self.redis_client.sunion(*grade_keys)
        except Exception:
            logger.exception("users:by_grade SUNION 실패: stock_code=%s, tier=%s", stock_code, tier)
            return []

        return sorted(int(uid) for uid in raw_ids)


class MockBuyCandidateRepository:
    """테스트 환경용 Mock Repository."""

    def __init__(self, store: dict[str, list[int]] | None = None) -> None:
        self._store: dict[str, list[int]] = store or {}

    def get_eligible_user_ids(self, stock_code: str) -> list[int]:
        return self._store.get(stock_code, [])
