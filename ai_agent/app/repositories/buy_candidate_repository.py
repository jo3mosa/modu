import logging
from typing import Protocol

from app.config.redis import get_redis_client

logger = logging.getLogger(__name__)

_KEY_RISK_TIER = "stock:risk_tier"
_KEY_USERS_BY_GRADE = "users:by_grade"


class BuyCandidateRepository(Protocol):
    def get_eligible_user_grades(self, stock_code: str) -> tuple[int | None, dict[int, int]]:
        """(stock_tier, {user_id: risk_grade}) 반환. tier 미설정 시 (None, {})."""
        ...


class RedisBuyCandidateRepository:
    """
    Redis를 직접 읽어 매수 추천 적격 유저와 등급을 조회한다.

    사용 키:
        stock:risk_tier:{stock_code}   — DA 배치가 SET. 종목별 risk tier (1~5 string)
        users:by_grade:{1~5}           — 백엔드가 SADD/SREM. 등급별 유저 ID Set

    조회 로직:
        1. stock:risk_tier:{code} → tier T
        2. SMEMBERS users:by_grade:{5} ~ SMEMBERS users:by_grade:{T} 순으로 조회
           → 유저별 실제 risk_grade 추적 (높은 등급 우선)
        3. tier 미설정이면 (None, {}) 반환
    """

    def __init__(self) -> None:
        self.redis_client = get_redis_client()

    def get_eligible_user_grades(self, stock_code: str) -> tuple[int | None, dict[int, int]]:
        try:
            tier_raw = self.redis_client.get(f"{_KEY_RISK_TIER}:{stock_code}")
        except Exception:
            logger.exception("stock:risk_tier 조회 실패: stock_code=%s", stock_code)
            return None, {}

        if tier_raw is None:
            logger.info("stock:risk_tier 미설정 (DA 배치 미실행) - 비보유자 매칭 생략: stock_code=%s", stock_code)
            return None, {}

        try:
            tier = int(tier_raw)
        except (ValueError, TypeError):
            logger.warning("stock:risk_tier 파싱 실패 - 비보유자 매칭 생략: stock_code=%s, value=%r", stock_code, tier_raw)
            return None, {}

        if not (1 <= tier <= 5):
            logger.warning("stock:risk_tier 범위 초과 - 비보유자 매칭 생략: stock_code=%s, tier=%s", stock_code, tier)
            return None, {}

        # 높은 등급부터 순회해 유저별 실제 grade를 추적한다.
        # 동일 유저가 여러 Set에 존재하는 데이터 오류 시 가장 높은 등급이 우선 적용된다.
        user_grade: dict[int, int] = {}
        for grade in range(5, tier - 1, -1):
            try:
                members = self.redis_client.smembers(f"{_KEY_USERS_BY_GRADE}:{grade}")
            except Exception:
                logger.exception("users:by_grade 조회 실패: stock_code=%s, grade=%s", stock_code, grade)
                continue
            for uid_raw in members:
                try:
                    uid = int(uid_raw)
                    if uid not in user_grade:
                        user_grade[uid] = grade
                except (ValueError, TypeError):
                    logger.warning("users:by_grade 유저 ID 파싱 실패 - 건너뜀: stock_code=%s, grade=%s, value=%r", stock_code, grade, uid_raw)

        return tier, user_grade


class MockBuyCandidateRepository:
    """테스트 환경용 Mock Repository."""

    def __init__(
        self,
        store: dict[str, list[int]] | None = None,
        tier: int = 3,
    ) -> None:
        self._store: dict[str, list[int]] = store or {}
        self._tier = tier

    def get_eligible_user_grades(self, stock_code: str) -> tuple[int | None, dict[int, int]]:
        ids = self._store.get(stock_code, [])
        if not ids:
            return None, {}
        return self._tier, {uid: self._tier for uid in ids}
