import json
import logging
from typing import Protocol

from functools import lru_cache

from sqlalchemy import text
from sqlalchemy.engine import Engine

from app.config.redis import get_redis_client
from app.context.user_context import create_engine_from_env


@lru_cache(maxsize=1)
def _get_db_engine() -> Engine:
    return create_engine_from_env()

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

        return parsed


class PostgresPortfolioSnapshotRepository:
    """
    position_thresholds + daily_portfolio_snapshots + stock_master 테이블에서
    사용자 포트폴리오 스냅샷을 조회한다.

    반환 예시:
        {
            "cash_balance": 1000000,
            "positions": [
                {
                    "stock_code": "005930",
                    "stock_name": "삼성전자",
                    "quantity": 10,
                    "average_price": 75000,
                }
            ],
        }
    """

    def get(self, user_id: int) -> dict:
        try:
            with _get_db_engine().connect() as conn:
                cash_row = conn.execute(
                    text("""
                        SELECT available_cash
                        FROM daily_portfolio_snapshots
                        WHERE user_id = :user_id
                        ORDER BY snapshot_date DESC
                        LIMIT 1
                    """),
                    {"user_id": user_id},
                ).fetchone()

                position_rows = conn.execute(
                    text("""
                        SELECT pt.stock_code,
                               sm.stock_name,
                               pt.quantity,
                               pt.avg_entry_price
                        FROM position_thresholds pt
                        JOIN stock_master sm ON pt.stock_code = sm.stock_code
                        WHERE pt.user_id = :user_id AND pt.is_active = TRUE
                    """),
                    {"user_id": user_id},
                ).fetchall()
        except Exception:
            logger.exception("DB 포트폴리오 스냅샷 조회 실패: user_id=%s", user_id)
            raise

        positions = [
            {
                "stock_code": row.stock_code,
                "stock_name": row.stock_name,
                "quantity": row.quantity,
                "average_price": row.avg_entry_price,
            }
            for row in position_rows
        ]

        return {
            "cash_balance": cash_row.available_cash if cash_row else 0,
            "positions": positions,
        }


class MockPortfolioSnapshotRepository:
    """
    테스트 환경에서 사용할 Mock Repository.
    """

    def __init__(self, store: dict[int, dict] | None = None) -> None:
        self._store: dict[int, dict] = store or {}

    def get(self, user_id: int) -> dict:
        return self._store.get(user_id, {})
