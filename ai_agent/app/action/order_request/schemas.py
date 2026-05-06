from typing import Literal

from pydantic import BaseModel, Field, model_validator


class OrderRequest(BaseModel):
    """
    Executor가 실제 주문 API를 호출하기 전에 사용하는 표준 주문 요청 모델.

    Supervisor Agent의 FinalDecision을 그대로 주문하지 않고,
    Risk Guard를 통과한 결정만 주문 가능한 형태로 변환한 결과
    """

    stock_code: str = Field(..., description="주문 대상 종목 코드")
    side: Literal["buy", "sell"] = Field(..., description="주문 방향")
    quantity: int = Field(..., gt=0, description="주문 수량")

    order_type: Literal["market", "limit"] = Field(
        default="market",
        description="시장가/지정가 주문 여부",
    )

    limit_price: int | None = Field(
        default=None,
        description="지정가 주문 가격. 시장가 주문이면 None",
    )

    order_amount: int | None = Field(
        default=None,
        description="주문 기준 금액",
    )

    reason: str = Field(
        default="",
        description="주문 요청 생성 사유",
    )

    @model_validator(mode="after")
    def validate_limit_order(self) -> "OrderRequest":
        """
        지정가 주문이면 limit_price가 반드시 필요
        """

        if self.order_type == "limit":
            if self.limit_price is None or self.limit_price <= 0:
                raise ValueError("지정가 주문은 limit_price가 0보다 커야 합니다.")

        return self