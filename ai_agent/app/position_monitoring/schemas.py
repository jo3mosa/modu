from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, Field


PositionEventType = Literal[
    "TARGET_PRICE_HIT",
    "STOP_LOSS_PRICE_HIT",
    "PROFIT_RATE_SPIKE",
]


class PriceTick(BaseModel):
    """
    Position Monitoring이 입력으로 받는 실시간 현재가 데이터.

    이 모델은 Kafka 메시지, WebSocket 현재가, 테스트용 mock 데이터 등
    어떤 입력원이든 동일한 구조로 처리하기 위한 내부 표준 포맷이다.
    """

    stock_code: str = Field(..., description="종목 코드")
    current_price: int = Field(..., gt=0, description="현재가")
    timestamp: datetime = Field(..., description="현재가 수신 시각")


class PositionEvent(BaseModel):
    """
    사용자 보유 포지션에서 발생한 이벤트.

    목표가/손절가 도달처럼 즉시 주문 후보 생성이 필요한 이벤트와,
    손익률 급변처럼 Reasoning Layer 판단이 필요한 이벤트를 함께 표현한다.
    """

    user_id: int = Field(..., description="사용자 ID")
    stock_code: str = Field(..., description="종목 코드")
    event_type: PositionEventType = Field(..., description="포지션 이벤트 타입")
    current_price: int = Field(..., gt=0, description="이벤트 발생 당시 현재가")
    threshold: dict[str, Any] = Field(
        default_factory=dict,
        description="사용자가 설정한 목표가/손절가/기준 수익률 정보",
    )
    timestamp: datetime = Field(..., description="이벤트 발생 시각")