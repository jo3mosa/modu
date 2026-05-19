from typing import Literal

from pydantic import BaseModel, Field, field_validator, model_validator


class ResearchVerdict(BaseModel):
    """
    Strategy Manager(Research Manager)가 Bull/Bear 토론을 종합해 내리는 판결.

    역할:
    - 양측 주장의 핵심 근거를 비교하고 어느 쪽 논리가 더 견고한지 판단한다.
    - 단일 종목과 매매 방향을 결정하고, 매매 시 주문 파라미터까지 산출한다.
    - 후속 Critic/Supervisor 단계는 이 판결을 StrategyDraft로 변환한 값을 받는다.
      - winning_side: "bull" / "bear" / "balanced"
      - asset: 최종 종목 코드 (반드시 후보 중 하나)
      - recommended_side: "buy" / "sell" / "hold"
      - rationale: 판결 근거 요약
      - key_bull_points: Bull 주장 핵심 정리
      - key_bear_points: Bear 주장 핵심 정리
      - confidence: 판결 신뢰도 점수
      - order_amount: 주문 금액 (hold일 때 0)
      - target_price: 목표가 (buy/sell 필수)
      - stop_loss_price: 손절가 (buy/sell 필수)
    """
    winning_side: Literal["bull", "bear", "balanced"]
    asset: str
    recommended_side: Literal["buy", "sell", "hold"]
    rationale: str = ""
    key_bull_points: list[str] = Field(default_factory=list)
    key_bear_points: list[str] = Field(default_factory=list)
    confidence: float = 0.0
    order_amount: int = 0
    target_price: int | None = None
    stop_loss_price: int | None = None

    @field_validator("confidence", mode="before")
    @classmethod
    def normalize_confidence(cls, v: float) -> float:
        if v is not None and v > 1.0:
            return v / 100.0
        return v

    @model_validator(mode="after")
    def validate_trade_params(self) -> "ResearchVerdict":
        if self.recommended_side != "hold" and not self.asset:
            raise ValueError("recommended_side가 buy/sell일 때 asset은 필수입니다.")
        if self.recommended_side == "hold":
            if self.order_amount != 0:
                raise ValueError("recommended_side가 hold일 때 order_amount는 0이어야 합니다.")
            if self.target_price is not None:
                raise ValueError("recommended_side가 hold일 때 target_price는 None이어야 합니다.")
            if self.stop_loss_price is not None:
                raise ValueError("recommended_side가 hold일 때 stop_loss_price는 None이어야 합니다.")
        else:
            if self.target_price is None:
                raise ValueError(
                    f"recommended_side가 {self.recommended_side}일 때 target_price는 필수입니다."
                )
            if self.stop_loss_price is None:
                raise ValueError(
                    f"recommended_side가 {self.recommended_side}일 때 stop_loss_price는 필수입니다."
                )
            if self.target_price <= 0:
                raise ValueError(
                    f"recommended_side가 {self.recommended_side}일 때 target_price는 0보다 커야 합니다."
                )
            if self.stop_loss_price <= 0:
                raise ValueError(
                    f"recommended_side가 {self.recommended_side}일 때 stop_loss_price는 0보다 커야 합니다."
                )
            if self.order_amount <= 0:
                raise ValueError(
                    f"recommended_side가 {self.recommended_side}일 때 order_amount는 0보다 커야 합니다."
                )
        return self


class StrategyDraft(BaseModel):
    """
    Strategy Agent가 생성하는 투자 전략 초안

    이 객체는 이후 Critic Agent의 피드백과 Supervisor Agent의 최종 판단에 활용
      - asset: 투자 대상 종목 코드 (예: "005930") — 반드시 후보 종목 중 하나
      - side: 주문 방향 ("buy", "sell", "hold") — hold는 전략 보류 권고
      - order_amount: 주문 금액 — hold일 때는 반드시 0
      - target_price: 목표 매수가격 — buy/sell 필수, hold는 None
      - stop_loss_price: 손절 가격 — buy/sell 필수, hold는 None
      - reason: 전략 생성 근거
      - confidence: 전략 신뢰도 점수
    """
    asset: str
    side: Literal["buy", "sell", "hold"]
    order_amount: int
    target_price: int | None = None
    stop_loss_price: int | None = None
    reason: str = ""
    confidence: float = 0.0

    @model_validator(mode="after")
    def validate_side_constraints(self) -> "StrategyDraft":
        if self.side == "hold":
            if self.order_amount != 0:
                raise ValueError("side가 hold일 때 order_amount는 0이어야 합니다.")
            if self.target_price is not None:
                raise ValueError("side가 hold일 때 target_price는 None이어야 합니다.")
            if self.stop_loss_price is not None:
                raise ValueError("side가 hold일 때 stop_loss_price는 None이어야 합니다.")
        else:
            if self.target_price is None:
                raise ValueError(f"side가 {self.side}일 때 target_price는 필수입니다.")
            if self.stop_loss_price is None:
                raise ValueError(f"side가 {self.side}일 때 stop_loss_price는 필수입니다.")
        return self


class ExpectedScenario(BaseModel):
    """
    Decision Manager가 생성하는 예상 시나리오

    사용자 화면에 보여줄 설명이나 판단 근거 로그에 활용
    - base: 기본 시나리오 설명
    - bear: 하락/위험 시나리오 설명
    - bull: 상승/기회 시나리오 설명
    """
    base: str = ""
    bear: str = ""
    bull: str = ""


class FinalDecision(BaseModel):
    """
    Decision Manager가 내리는 최종 투자 결정

    Risk Gate가 이 객체의 형식·정책을 검증하고, 통과 시 ai.decision.generated 토픽으로
    백엔드(KisOrderConsumer)에 전달되어 실제 KIS 주문이 실행된다.
      - action: 최종 행동 결정 ("trade" 또는 "hold")
      - asset: 거래 대상 종목
      - side: 주문 방향
      - order_amount: 주문 금액
      - target_price: 목표 매수가격
      - stop_loss_price: 손절 가격
      - reason_summary: 최종 결정에 대한 간략한 설명
      - risk_summary: 주요 리스크 요약
      - expected_scenario: 예상 시나리오 설명
      - confidence: 최종 결정에 대한 신뢰도 점수
      - risk_level: Decision Manager가 평가한 리스크 등급. Risk Gate가 high이면 사용자 승인 요구.
      - user_message: 사용자에게 보여줄 간단한 판단 사유
    """
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
    risk_level: Literal["low", "medium", "high"] = "low"
    user_message: str = ""

    @field_validator("confidence", mode="before")
    @classmethod
    def normalize_confidence(cls, v: float) -> float:
        if v is not None and v > 1.0:
            return v / 100.0
        return v
