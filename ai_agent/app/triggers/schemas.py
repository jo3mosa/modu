"""
Trigger 이벤트 스키마.

DA 팀이 합의해 준 Analysis Layer 명세를 단일 소스로 따른다.
- MarketTriggerEvent: Analysis Layer가 발행하는 시장 단위 이벤트 (명세 그대로)
- UserTriggerEvent: User Trigger Matcher가 사용자 정보와 결합한 실행 이벤트
- PositionTriggerEvent: Position Monitoring이 발행하는 사용자 포지션 이벤트
"""
from datetime import datetime
from enum import Enum
from typing import Any, Literal
from uuid import uuid4

from pydantic import BaseModel, Field


class StrEnum(str, Enum):
    pass


class TriggerType(StrEnum):
    MARKET_EVENT = "MARKET_EVENT"
    POSITION_EVENT = "POSITION_EVENT"
    SCHEDULE_EVENT = "SCHEDULE_EVENT"


class MarketTrigger(BaseModel):
    """
    Analysis Layer가 발행하는 Market Event의 trigger 메타데이터.

    - rule_ids: Analysis Layer 명세가 정의한 rule ID 목록 (예: ["RSI-002"], ["DART-004"]).
      코드/필터/로그 분석은 이 값을 정형 키로 사용한다.
    - trigger_reason: 사람이 읽는 한국어 사유 목록 (예: ["RSI 과매수"]).
      LangSmith / 사용자 노출 메시지 등에서 사용한다.
    """

    rule_ids: list[str] = Field(default_factory=list)
    trigger_reason: list[str] = Field(default_factory=list)


class MarketTriggerEvent(BaseModel):
    """
    Analysis Layer가 발행하는 시장 단위 이벤트.

    Analysis Layer 명세(Kafka 메시지 포맷)를 그대로 따른다. 아직 특정 사용자와 연결되지 않은
    상태이며, User Trigger Matcher가 사용자 포트폴리오/관심 종목과 매칭한다.

    명세 페이로드 예시:
    {
      "event_type": "MARKET_EVENT",
      "stock_code": "005930",
      "timestamp": "2026-04-28T08:00:00Z",
      "trigger": {"rule_ids": ["RSI-002"], "trigger_reason": ["RSI 과매수"]},
      "analysis_snapshot": {"technical": {...}, "fundamental": {...}, ...}
    }

    `event_id`는 명세에 없는 AI 측 추적용 보조 필드 — Analysis Layer 메시지에 없으면
    수신 시점에 자동 생성되어 LangSmith / 로그 / source_event_id에 사용된다.
    """

    event_type: Literal["MARKET_EVENT"] = "MARKET_EVENT"
    stock_code: str = Field(..., description="이벤트가 발생한 종목 코드")
    timestamp: datetime = Field(..., description="이벤트 발생 시각")
    trigger: MarketTrigger = Field(default_factory=MarketTrigger)
    analysis_snapshot: dict[str, Any] = Field(default_factory=dict)

    # AI 측 추적용 보조. Analysis Layer 메시지에 없으면 수신 시 자동 생성.
    event_id: str = Field(default_factory=lambda: f"market_event_{uuid4()}")


class PositionTriggerEvent(BaseModel):
    """
    Position Monitoring이 발행하는 사용자 포지션 이벤트.

    목표가/손절가 근접, 수익률 급변 등 사용자별 포지션 조건에서 생성된다.
    Market Event와 달리 발행 시점부터 user_id가 결정되어 있다.
    """

    event_id: str = Field(default_factory=lambda: f"position_event_{uuid4()}")
    event_type: Literal["POSITION_EVENT"] = "POSITION_EVENT"
    timestamp: datetime = Field(..., description="이벤트 발생 시각")

    user_id: int = Field(..., description="이벤트 대상 사용자 ID")
    stock_code: str = Field(..., description="이벤트 대상 종목 코드")

    trigger: MarketTrigger = Field(default_factory=MarketTrigger)
    position_snapshot: dict[str, Any] = Field(default_factory=dict)


class UserTriggerEvent(BaseModel):
    """
    Reasoning Layer 실행 직전에 사용되는 사용자별 실행 이벤트.

    Market Event 또는 Position Event가 사용자 정보(portfolio_snapshot, user_context)와
    결합된 최종 입력. LangGraph는 이 이벤트를 InvestmentAgentState로 변환해 실행한다.
    """

    event_id: str = Field(default_factory=lambda: f"user_trigger_{uuid4()}")
    source_event_id: str | None = Field(default=None, description="원본 Market/Position Event ID")
    event_type: TriggerType = TriggerType.MARKET_EVENT
    timestamp: datetime = Field(..., description="원본 이벤트 발생 시각")

    user_id: int = Field(..., description="Reasoning Layer 실행 대상 사용자 ID")
    stock_code: str = Field(..., description="판단 대상 종목 코드")

    trigger: MarketTrigger = Field(default_factory=MarketTrigger)
    analysis_snapshot: dict[str, Any] = Field(default_factory=dict)

    portfolio_snapshot: dict[str, Any] = Field(default_factory=dict)
    user_context: dict[str, Any] = Field(default_factory=dict)
