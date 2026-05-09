from typing import Literal, Protocol, TypedDict


Decision = Literal["BUY", "SELL", "HOLD"]
OrderStatus = Literal["PENDING", "FILLED", "CANCELLED", "REJECTED"]


class PastDecision(TypedDict):
    ai_judgment_id: int
    user_id: int
    judged_at: str
    stock_code: str
    stock_name: str
    sector: str | None
    risk_grade: str | None

    decision: Decision
    confidence_score: int
    judgment_reason: str
    key_signals: list[str]
    bull_claim: str | None
    bear_claim: str | None

    order_id: int | None
    order_status: OrderStatus | None
    realized_profit_loss_rate: float | None


class DecisionLog(TypedDict):
    user_id: int
    stock_code: str
    sector: str | None
    risk_grade: str | None

    decision: Decision
    order_amount: int

    judgment_reason: str
    key_signals: list[str]
    confidence_score: int
    bull_claim: str | None
    bear_claim: str | None


class PostMortemRecord(TypedDict):
    ai_judgment_id: int
    trade_pnl_record_id: int | None
    entry_timing_assessment: str
    target_price_assessment: str
    risk_prediction_accuracy: str
    missed_signals: list[str]
    lessons: list[str]
    summary: str


class MemoryStore(Protocol):
    def get_recent_decisions(
        self,
        user_id: int,
        stock_codes: list[str],
        sectors: list[str],
        key_signals: list[str],
        limit: int = 10,
    ) -> list[PastDecision]: ...

    def get_similar_decisions(
        self,
        user_id: int,
        stock_codes: list[str],
        sectors: list[str],
        key_signals: list[str],
        limit: int = 5,
        only_loss: bool = False,
    ) -> list[PastDecision]: ...

    def store_decision(
        self,
        log: DecisionLog,
    ) -> int: ...

    def store_postmortem(
        self,
        report: PostMortemRecord,
    ) -> int: ...