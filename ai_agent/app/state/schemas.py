from typing import Literal

from pydantic import BaseModel, Field


class StrategyDraft(BaseModel):
    asset: str
    side: Literal["buy", "sell"]
    order_amount: int
    target_price: float | None = None
    stop_loss_price: float | None = None
    reason: str = ""
    confidence: float = 0.0


class CriticFeedback(BaseModel):
    approved: bool
    risk_level: Literal["low", "medium", "high"]
    comments: list[str] = Field(default_factory=list)


class ExpectedScenario(BaseModel):
    base: str = ""
    bear: str = ""
    bull: str = ""


class FinalDecision(BaseModel):
    action: Literal["trade", "hold"]
    asset: str | None = None
    side: Literal["buy", "sell"] | None = None
    order_amount: int | None = None
    target_price: float | None = None
    stop_loss_price: float | None = None
    reason_summary: str = ""
    risk_summary: list[str] = Field(default_factory=list)
    expected_scenario: ExpectedScenario = Field(default_factory=ExpectedScenario)
    confidence: float = 0.0
    user_message: str = ""
