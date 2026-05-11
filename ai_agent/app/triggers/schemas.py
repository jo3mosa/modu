from enum import Enum


class StrEnum(str, Enum):
    pass
from typing import Any

from pydantic import BaseModel, Field


class TriggerType(StrEnum):
    MARKET_EVENT = "MARKET_EVENT"
    POSITION_EVENT = "POSITION_EVENT"
    SCHEDULE_EVENT = "SCHEDULE_EVENT"


class MarketTriggerEvent(BaseModel):
    """
    Analysis Layer가 생성하는 시장 단위 이벤트.

    아직 특정 사용자와 연결되지 않은 이벤트이며,
    User Trigger Matcher가 이 이벤트를 사용자 포트폴리오/관심 종목과 매칭한다.
    """

    event_id: str = Field(..., description="트리거 이벤트 고유 ID")
    trigger_type: TriggerType = Field(default=TriggerType.MARKET_EVENT)
    trigger_reason: list[str] = Field(default_factory=list)

    stock_code: str = Field(..., description="이벤트가 발생한 종목 코드")

    market_snapshot: dict[str, Any] = Field(default_factory=dict)
    analysis_snapshot: dict[str, Any] = Field(default_factory=dict)
    candidate_assets: list[dict[str, Any]] = Field(default_factory=list)

    source: str = Field(default="analysis_layer")


class UserTriggerEvent(BaseModel):
    """
    Reasoning Layer 실행 직전에 사용되는 사용자별 실행 이벤트.

    Market Event 또는 Position Event가 사용자 정보와 결합된 최종 입력 이벤트이다.
    LangGraph는 이 이벤트를 InvestmentAgentState로 변환한 뒤 실행된다.
    """

    event_id: str = Field(..., description="트리거 이벤트 고유 ID")
    source_event_id: str | None = Field(default=None, description="원본 Market/Position Event ID")

    trigger_type: TriggerType
    trigger_reason: list[str] = Field(default_factory=list)

    user_id: int = Field(..., description="Reasoning Layer 실행 대상 사용자 ID")
    stock_code: str = Field(..., description="판단 대상 종목 코드")

    market_snapshot: dict[str, Any] = Field(default_factory=dict)
    analysis_snapshot: dict[str, Any] = Field(default_factory=dict)
    candidate_assets: list[dict[str, Any]] = Field(default_factory=list)
    portfolio_snapshot: dict[str, Any] = Field(default_factory=dict)
    user_context: dict[str, Any] = Field(default_factory=dict)

    source: str = Field(default="user_trigger_matcher")