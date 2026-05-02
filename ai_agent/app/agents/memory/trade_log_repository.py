import os
from typing import Any

from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine


class TradeLogRepository:
    def __init__(self, database_url: str | None = None) -> None:
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
        with self.engine.connect() as conn:
            rows = conn.execute(query, params).mappings().all()

        return [dict(row) for row in rows]