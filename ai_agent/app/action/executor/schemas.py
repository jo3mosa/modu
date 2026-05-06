from typing import Literal

from pydantic import BaseModel, Field


class ExecutionResult(BaseModel):
    """
    Executor의 주문 실행 결과.

    실제 broker API 호출 성공 여부와
    주문 요청 생성 성공 여부를 함께 표현한다.
    """

    status: Literal[
        "success",
        "failed",
        "skipped",
    ] = Field(..., description="주문 실행 결과 상태")

    order_id: str | None = Field(
        default=None,
        description="브로커 주문 ID",
    )

    stock_code: str | None = Field(
        default=None,
        description="주문 종목 코드",
    )

    side: Literal["buy", "sell"] | None = Field(
        default=None,
        description="주문 방향",
    )

    quantity: int | None = Field(
        default=None,
        description="주문 수량",
    )

    reason: str = Field(
        default="",
        description="주문 실행 결과 설명",
    )