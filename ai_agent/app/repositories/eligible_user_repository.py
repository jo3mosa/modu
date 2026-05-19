"""eligible_user_repository

트리거 라우팅 확장(S14P31B106-350)용 PG 조회 Repository.

기존 user_trigger_matcher 는 종목 보유자에게만 트리거를 발송했지만,
새 로직은 *종목 위험도(risk_tier) ↔ 유저 위험성향(risk_grade)* 매칭을 추가해
보유하지 않은 종목에 대해서도 매수/매도 판단을 진행시킨다.

이 Repository 가 제공하는 두 조회:
    1. 종목 → 최신 risk_tier  (analysis_server 의 compute_risk_tier 결과 활용)
    2. tier → tier 매칭 + auto_trade ACTIVE 인 user_id 목록

매칭 규칙: user.risk_grade >= trigger.tier
    (tier 1 종목 트리거 → 1~5단계 유저 / tier 3 → 3·4·5단계 유저)

자동매매 OFF 유저는 backend SignalHandlerService 에서 어차피 BLOCKED 되지만,
ai_agent 단에서 사전 필터해 LLM/추론 비용을 절약 (S14P31B106-350 정책).

이 모듈은 read-only. row 변경은 backend 가 단독 책임 (auto_trade_settings,
investment_profiles 둘 다 backend 도메인).
"""

import logging
from typing import Protocol

from sqlalchemy import text
from sqlalchemy.engine import Engine

from app.context.user_context import create_engine_from_env

logger = logging.getLogger(__name__)


# ─── SQL ────────────────────────────────────────────────────────────────────

# 종목의 최신 risk_tier 1건. daily_fundamentals PK (stock_code, date) 인덱스로
# index-seek 1회 + 정렬 — 단일 row.
# risk_tier 가 NULL 인 row 도 그대로 반환 (compute_risk_tier 미실행 / 데이터 부족 케이스).
_STOCK_TIER_SQL = text("""
    SELECT risk_tier
    FROM daily_fundamentals
    WHERE stock_code = :stock_code
    ORDER BY date DESC
    LIMIT 1
""")

# tier 매칭 + 자동매매 ACTIVE 인 user_id 목록.
#
# risk_grade ↔ tier 매핑은 backend InvestmentRiskLevel enum 과 1:1 일치
# (STABLE=1 ~ AGGRESSIVE=5). risk_score 가 아닌 enum 이름을 truth 로 사용한 이유는
# backend 의 fromScore() 경계가 향후 조정돼도 enum 자체는 변하지 않기 때문.
# 매핑이 깨지면 즉시 NULL → 조건 미충족으로 안전하게 탈락.
_ELIGIBLE_USERS_SQL = text("""
    SELECT ip.user_id
    FROM investment_profiles ip
    JOIN auto_trade_settings ats ON ats.user_id = ip.user_id
    WHERE ats.auto_trade_status = 'ACTIVE'
      AND CASE ip.risk_grade
            WHEN 'STABLE'         THEN 1
            WHEN 'STABLE_SEEKING' THEN 2
            WHEN 'RISK_NEUTRAL'   THEN 3
            WHEN 'ACTIVE'         THEN 4
            WHEN 'AGGRESSIVE'     THEN 5
            ELSE NULL
          END >= :tier
    ORDER BY ip.user_id
""")


# ─── Protocol + 구현 ───────────────────────────────────────────────────────

class EligibleUserRepository(Protocol):
    """
    트리거 라우팅에서 사용할 두 조회를 제공하는 Repository 인터페이스.

    PG 직접 조회 구현체와 Mock 구현체가 같은 메서드 시그니처를 갖도록 맞춘다.
    """

    def get_stock_risk_tier(self, stock_code: str) -> int | None:
        """종목의 최신일 기준 risk_tier(1~5). 미분류/미존재 시 None."""
        ...

    def get_eligible_user_ids(self, tier: int) -> list[int]:
        """risk_grade ≥ tier AND auto_trade ACTIVE 인 user_id 목록 (정렬됨)."""
        ...


class PostgresEligibleUserRepository:
    """
    Postgres 직접 조회 구현체.

    engine 은 module-level 단일 인스턴스. SQLAlchemy 가 connection pool 을 관리.
    매 호출마다 새 connection 생성/해제하지 않고 pool 에서 빌려쓴다.
    """

    def __init__(self, engine: Engine | None = None) -> None:
        self.engine = engine or create_engine_from_env()

    def get_stock_risk_tier(self, stock_code: str) -> int | None:
        try:
            with self.engine.connect() as conn:
                row = conn.execute(
                    _STOCK_TIER_SQL, {"stock_code": stock_code}
                ).first()
        except Exception:
            logger.exception(
                "risk_tier 조회 실패: stock_code=%s", stock_code
            )
            raise

        if row is None or row.risk_tier is None:
            return None
        return int(row.risk_tier)

    def get_eligible_user_ids(self, tier: int) -> list[int]:
        if not 1 <= tier <= 5:
            # 정상 트리거 흐름에선 발생 X — fail fast.
            raise ValueError(f"risk_tier 는 1~5 사이여야 합니다: got={tier}")

        try:
            with self.engine.connect() as conn:
                rows = conn.execute(
                    _ELIGIBLE_USERS_SQL, {"tier": tier}
                ).all()
        except Exception:
            logger.exception("eligible user 조회 실패: tier=%d", tier)
            raise

        return [int(r.user_id) for r in rows]


class MockEligibleUserRepository:
    """
    테스트용 in-memory Mock.

    setter 메서드로 종목별 tier 와 tier 별 user 목록을 주입 가능.
    """

    def __init__(self) -> None:
        self._stock_tier: dict[str, int] = {}
        # tier -> 해당 tier 의 user_id 들 (실제 SQL 처럼 누적 매칭 결과를 직접 보유)
        self._eligible_users_by_tier: dict[int, list[int]] = {}

    def set_stock_tier(self, stock_code: str, tier: int | None) -> None:
        if tier is None:
            self._stock_tier.pop(stock_code, None)
        else:
            self._stock_tier[stock_code] = tier

    def set_eligible_users(self, tier: int, user_ids: list[int]) -> None:
        self._eligible_users_by_tier[tier] = sorted(set(user_ids))

    def get_stock_risk_tier(self, stock_code: str) -> int | None:
        return self._stock_tier.get(stock_code)

    def get_eligible_user_ids(self, tier: int) -> list[int]:
        if not 1 <= tier <= 5:
            raise ValueError(f"risk_tier 는 1~5 사이여야 합니다: got={tier}")
        return list(self._eligible_users_by_tier.get(tier, []))


# ─── 모듈 레벨 singleton + accessor ─────────────────────────────────────────

_ELIGIBLE_USER_REPOSITORY: EligibleUserRepository = PostgresEligibleUserRepository()


def get_eligible_user_repository() -> EligibleUserRepository:
    return _ELIGIBLE_USER_REPOSITORY
