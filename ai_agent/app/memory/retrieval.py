import json
from datetime import datetime
from typing import Any

from sqlalchemy import text
from sqlalchemy.engine import Engine

from app.memory.interfaces import PastDecision


class DecisionRetrieval:
    """ai_judgments 테이블에서 과거 AI 판단을 조회한다."""

    def __init__(self, engine: Engine) -> None:
        self.engine = engine

    def get_recent_decisions(
        self,
        user_id: int,
        stock_codes: list[str],
        sectors: list[str],
        key_signals: list[str],
        limit: int = 10,
        days: int = 30,
        as_of: datetime | None = None,
    ) -> list[PastDecision]:
        return self._query(
            user_id=user_id,
            stock_codes=stock_codes,
            sectors=sectors,
            key_signals=key_signals,
            limit=limit,
            days=days,
            only_loss=False,
            as_of=as_of,
        )

    def get_similar_decisions(
        self,
        user_id: int,
        stock_codes: list[str],
        sectors: list[str],
        key_signals: list[str],
        limit: int = 5,
        days: int = 30,
        only_loss: bool = False,
        as_of: datetime | None = None,
    ) -> list[PastDecision]:
        return self._query(
            user_id=user_id,
            stock_codes=stock_codes,
            sectors=sectors,
            key_signals=key_signals,
            limit=limit,
            days=days,
            only_loss=only_loss,
            as_of=as_of,
        )

    def _query(
        self,
        user_id: int,
        stock_codes: list[str],
        sectors: list[str],
        key_signals: list[str],
        limit: int,
        days: int,
        only_loss: bool,
        as_of: datetime | None = None,
    ) -> list[PastDecision]:
        # 그룹 내부(stock_codes / sectors / key_signals)는 OR, 그룹 간은 AND
        and_clauses: list[str] = []
        params: dict[str, Any] = {
            "user_id": user_id,
            "limit": limit,
            "days": days,
            "as_of": as_of,
        }

        if stock_codes:
            and_clauses.append("aj.stock_code = ANY(:stock_codes)")
            params["stock_codes"] = stock_codes

        if sectors:
            and_clauses.append("aj.sector = ANY(:sectors)")
            params["sectors"] = sectors

        if key_signals:
            # key_signals는 JSONB 타입이므로 && 대신 jsonb_array_elements_text + ANY로 OR 매칭
            and_clauses.append(
                "EXISTS ("
                "SELECT 1 FROM jsonb_array_elements_text(aj.key_signals) AS ks "
                "WHERE ks = ANY(:key_signals)"
                ")"
            )
            params["key_signals"] = key_signals

        if not and_clauses:
            return []

        where_conditions = [
            "aj.user_id = :user_id",
            "aj.judged_at >= COALESCE(:as_of, NOW()) - (:days * INTERVAL '1 day')",
            "aj.judged_at <= COALESCE(:as_of, NOW())",
            *and_clauses,
        ]

        if only_loss:
            where_conditions.append("tpr.net_pnl < 0")

        where_clause = " AND ".join(where_conditions)

        query = text(f"""
            SELECT
                aj.id                AS ai_judgment_id,
                aj.user_id,
                aj.judged_at,
                aj.stock_code,
                sm.stock_name,
                aj.sector,
                aj.risk_grade,
                aj.decision,
                aj.confidence_score,
                aj.judgment_reason,
                aj.key_signals,
                aj.target_price,
                aj.stop_loss_price,
                aj.bull_claim,
                aj.bear_claim,
                aj.order_id,
                aj.order_amount,
                o.status             AS order_status,
                CASE WHEN tpr.id IS NOT NULL
                     THEN tpr.net_pnl::float / NULLIF(tpr.avg_buy_price * tpr.quantity, 0)
                     ELSE NULL
                END                  AS realized_profit_loss_rate
            FROM ai_judgments aj
            LEFT JOIN stock_master sm      ON sm.stock_code = aj.stock_code
            LEFT JOIN orders o             ON o.id = aj.order_id
            LEFT JOIN trade_pnl_records tpr ON tpr.buy_order_id = aj.order_id
            WHERE {where_clause}
            ORDER BY aj.judged_at DESC
            LIMIT :limit
        """)

        with self.engine.connect() as conn:
            rows = conn.execute(query, params).mappings().all()

        return [self._to_past_decision(row) for row in rows]

    def _to_past_decision(self, row: Any) -> PastDecision:
        raw_key_signals = row["key_signals"]
        key_signals: list[str] = (
            raw_key_signals if isinstance(raw_key_signals, list)
            else json.loads(raw_key_signals)
            if raw_key_signals else []
        )
        return PastDecision(
            ai_judgment_id=row["ai_judgment_id"],
            user_id=row["user_id"],
            judged_at=row["judged_at"],
            stock_code=row["stock_code"],
            stock_name=row["stock_name"] or "",
            sector=row["sector"],
            risk_grade=row["risk_grade"],
            decision=row["decision"],
            confidence_score=row["confidence_score"],
            judgment_reason=row["judgment_reason"],
            key_signals=key_signals,
            target_price=row["target_price"],
            stop_loss_price=row["stop_loss_price"],
            bull_claim=row["bull_claim"],
            bear_claim=row["bear_claim"],
            order_id=row["order_id"],
            order_amount=row["order_amount"],
            order_status=row["order_status"],
            realized_profit_loss_rate=row["realized_profit_loss_rate"],
        )
