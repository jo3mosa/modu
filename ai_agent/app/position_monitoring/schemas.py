from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, Field


PositionEventType = Literal[
    "TAKE_PROFIT_RATE_HIT",
    "STOP_LOSS_RATE_HIT",
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

    사용자의 평균단가 기준 손익률을 계산하여
    익절/손절 규칙 도달 여부를 판단한다.
    """

    user_id: int = Field(..., description="사용자 ID")

    stock_code: str = Field(
        ...,
        description="종목 코드",
    )

    event_type: PositionEventType = Field(
        ...,
        description="포지션 이벤트 타입",
    )

    current_price: int = Field(
        ...,
        gt=0,
        description="이벤트 발생 당시 현재가",
    )

    profit_rate: float = Field(
        ...,
        description="현재 수익률(%)",
    )

    trade_rule: dict[str, Any] = Field(
        default_factory=dict,
        description="사용자별 자동매매 규칙",
    )

    position: dict[str, Any] = Field(
        default_factory=dict,
        description="사용자의 현재 보유 포지션 정보",
    )

    timestamp: datetime = Field(
        ...,
        description="이벤트 발생 시각",
    )