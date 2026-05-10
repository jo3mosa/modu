import json
from typing import Any

from sqlalchemy import text
from sqlalchemy.engine import Engine

from app.memory.interfaces import DecisionLog, PostMortemRecord


class MemoryLog:
    """ai_judgments, post_mortem_reports 테이블에 새 판단과 복기를 저장한다."""

    def __init__(self, engine: Engine) -> None:
        self.engine = engine

    def store_decision(self, log: DecisionLog) -> int:
        """새 AI 판단을 ai_judgments에 INSERT하고 생성된 id를 반환한다."""
        query = text("""
            INSERT INTO ai_judgments (
                user_id,
                stock_code,
                sector,
                risk_grade,
                decision,
                order_amount,
                target_price,
                stop_loss_price,
                judgment_reason,
                key_signals,
                confidence_score,
                bull_claim,
                bear_claim,
                winning_side,
                expected_scenario,
                indicators_snapshot,
                judged_at
            ) VALUES (
                :user_id,
                :stock_code,
                :sector,
                :risk_grade,
                :decision,
                :order_amount,
                :target_price,
                :stop_loss_price,
                :judgment_reason,
                CAST(:key_signals AS jsonb),
                :confidence_score,
                :bull_claim,
                :bear_claim,
                :winning_side,
                :expected_scenario,
                CAST(:indicators_snapshot AS jsonb),
                NOW()
            )
            RETURNING id
        """)

        params: dict[str, Any] = {
            "user_id": log["user_id"],
            "stock_code": log["stock_code"],
            "sector": log.get("sector"),
            "risk_grade": log.get("risk_grade"),
            "decision": log["decision"],
            "order_amount": log["order_amount"],
            "target_price": log.get("target_price"),
            "stop_loss_price": log.get("stop_loss_price"),
            "judgment_reason": log["judgment_reason"],
            "key_signals": json.dumps(log["key_signals"]),
            "confidence_score": log["confidence_score"],
            "bull_claim": log.get("bull_claim"),
            "bear_claim": log.get("bear_claim"),
            "winning_side": log.get("winning_side"),
            "expected_scenario": log.get("expected_scenario"),
            "indicators_snapshot": json.dumps({}),
        }

        with self.engine.begin() as conn:
            row = conn.execute(query, params).mappings().first()

        if row is None:
            raise RuntimeError("ai_judgments INSERT failed: no id returned")

        return row["id"]

    def store_postmortem(self, report: PostMortemRecord) -> int:
        """사후 복기를 post_mortem_reports에 INSERT하고 생성된 id를 반환한다.

        user_id는 ai_judgment_id를 통해 ai_judgments에서 서브쿼리로 조회한다.
        """
        query = text("""
            INSERT INTO post_mortem_reports (
                user_id,
                ai_judgment_id,
                trade_pnl_record_id,
                entry_timing_assessment,
                exit_rule_assessment,
                risk_prediction_accuracy,
                missed_signals,
                lessons,
                summary,
                created_at
            ) VALUES (
                (SELECT user_id FROM ai_judgments WHERE id = :ai_judgment_id),
                :ai_judgment_id,
                :trade_pnl_record_id,
                :entry_timing_assessment,
                :exit_rule_assessment,
                :risk_prediction_accuracy,
                CAST(:missed_signals AS jsonb),
                CAST(:lessons AS jsonb),
                :summary,
                NOW()
            )
            RETURNING id
        """)

        params: dict[str, Any] = {
            "ai_judgment_id": report["ai_judgment_id"],
            "trade_pnl_record_id": report.get("trade_pnl_record_id"),
            "entry_timing_assessment": report["entry_timing_assessment"],
            "exit_rule_assessment": report["exit_rule_assessment"],
            "risk_prediction_accuracy": report["risk_prediction_accuracy"],
            "missed_signals": json.dumps(report["missed_signals"]),
            "lessons": json.dumps(report["lessons"]),
            "summary": report["summary"],
        }

        with self.engine.begin() as conn:
            row = conn.execute(query, params).mappings().first()

        if row is None:
            raise RuntimeError("post_mortem_reports INSERT failed: no id returned")

        return row["id"]
