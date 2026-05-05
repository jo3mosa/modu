import os
from typing import Any

from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine


class TradeLogRepository:
    """
    trade_logs DB 조회 전용 Repository.

    역할:
    - Memory Agent가 직접 SQL을 알지 않도록 DB 접근 책임을 분리한다.
    - 현재 종목/섹터/key_signal과 유사한 최근 거래를 조회한다.
    - 최근 손실 거래를 별도로 조회한다.
    """

    def __init__(self, database_url: str | None = None) -> None:
        """
        DB 연결 정보를 초기화한다.

        database_url 우선순위:
        1. 생성자 인자로 받은 database_url
        2. 환경변수 DATABASE_URL
        """

        self.database_url = database_url or os.getenv("DATABASE_URL")

        if not self.database_url:
            raise ValueError("DATABASE_URL is not set.")

        self.engine: Engine = create_engine(self.database_url)

    def find_recent_similar_trades(
        self,
        user_id: str,
        tickers: list[str],
        sectors: list[str],
        key_signals: list[str],
        limit: int = 10,
    ) -> list[dict[str, Any]]:
        """
        현재 판단 대상과 유사한 최근 거래를 조회한다.

        유사성 기준:
        - 같은 사용자
        - 실행 완료된 거래
        - ticker, sector, key_signals 중 하나 이상 일치
        """

        return self._find_trades(
            user_id=user_id,
            tickers=tickers,
            sectors=sectors,
            key_signals=key_signals,
            limit=limit,
            only_loss=False,
        )

    def find_recent_loss_trades(
        self,
        user_id: str,
        tickers: list[str],
        sectors: list[str],
        key_signals: list[str],
        limit: int = 5,
    ) -> list[dict[str, Any]]:
        """
        현재 판단 대상과 유사한 최근 손실 거래를 조회한다.
        """

        return self._find_trades(
            user_id=user_id,
            tickers=tickers,
            sectors=sectors,
            key_signals=key_signals,
            limit=limit,
            only_loss=True,
        )

    def _find_trades(
        self,
        user_id: str,
        tickers: list[str],
        sectors: list[str],
        key_signals: list[str],
        limit: int,
        only_loss: bool,
    ) -> list[dict[str, Any]]:
        """
        실제 SQL을 구성하고 실행하는 내부 메서드.

        tickers/sectors/key_signals 중 값이 있는 조건만 WHERE 절에 포함한다.
        """

        or_clauses: list[str] = []
        params: dict[str, Any] = {
            "user_id": user_id,
            "limit": limit,
        }

        if tickers:
            or_clauses.append("ticker = ANY(:tickers)")
            params["tickers"] = tickers

        if sectors:
            or_clauses.append("sector = ANY(:sectors)")
            params["sectors"] = sectors

        if key_signals:
            # key_signals 컬럼이 PostgreSQL text[] 타입이라는 전제.
            # && 연산자는 배열 간 겹치는 원소가 있는지 확인한다.
            or_clauses.append("key_signals && CAST(:key_signals AS text[])")
            params["key_signals"] = key_signals

        if not or_clauses:
            return []

        where_conditions = [
            "user_id = :user_id",
            "execution_status = 'executed'",
            f"({' OR '.join(or_clauses)})",
        ]

        if only_loss:
            where_conditions.append("realized_profit_loss_rate < 0")

        where_clause = " AND ".join(where_conditions)

        query = text(
            f"""
            SELECT
                trade_id,
                user_id,
                created_at,
                decision_type,
                execution_status,
                order_side,
                order_quantity,
                order_price,
                reason_summary,
                key_signals,
                result_status,
                realized_profit_loss_rate,
                ticker,
                name,
                sector,
                stock_risk_grade
            FROM trade_logs
            WHERE {where_clause}
            ORDER BY created_at DESC
            LIMIT :limit
            """
        )

        return self._fetch_all(query, params)

    def _fetch_all(self, query: Any, params: dict[str, Any]) -> list[dict[str, Any]]:
        """
        SQL을 실행하고 결과를 list[dict] 형태로 반환한다.

        with self.engine.connect():
        - 쿼리 실행 후 커넥션을 자동 반환한다.

        .mappings().all():
        - SQLAlchemy Row 객체를 dict처럼 접근 가능한 Mapping 형태로 변환한다.
        """

        with self.engine.connect() as conn:
            rows = conn.execute(query, params).mappings().all()

        return [dict(row) for row in rows]