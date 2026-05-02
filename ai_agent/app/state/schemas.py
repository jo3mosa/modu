from typing import Literal

from pydantic import BaseModel, Field


class StrategyDraft(BaseModel):
    """
    Strategy Agent가 생성하는 투자 전략 초안

    이 객체는 이후 Critic Agent의 피드백과 Supervisor Agent의 최종 판단에 활용
      - asset: 투자 대상 종목 코드 (예: "AAPL", "BTC-USD")
      - side: 주문 방향 ("buy" 또는 "sell")
      - order_amount: 주문 금액
      - target_price: 목표 매수가격 (선택)
      - stop_loss_price: 손절 가격 (선택)
      - reason: 전략 생성 근거
      - confidence: 전략 신뢰도 점수
    """
    asset: str
    side: Literal["buy", "sell"]
    order_amount: int
    target_price: float | None = None
    stop_loss_price: float | None = None
    reason: str = ""
    confidence: float = 0.0


class CriticFeedback(BaseModel):
    """
    Critic Agent가 StrategyDraft를 검토한 결과
    
    Supervisor Agent는 이 값을 보고 최종 거래 여부를 결정
      - approved: 전략 승인 여부 (True/False)
      - risk_level: 전략의 리스크 수준 ("low", "medium", "high")
      - comments: 리스크 평가에 대한 상세 코멘트 리스트
    """
    approved: bool
    risk_level: Literal["low", "medium", "high"]
    comments: list[str] = Field(default_factory=list)


class ExpectedScenario(BaseModel):
    """
    Supervisor Agent가 생성하는 예상 시나리오
    
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
    Supervisor Agent가 내리는 최종 투자 결정
    
    Risk Guard와 Executor는 이 객체를 기준으로 주문 가능 여부와 실행 여부를 판단
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
    user_message: str = ""
