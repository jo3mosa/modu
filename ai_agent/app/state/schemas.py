from typing import Literal

from pydantic import BaseModel, Field, model_validator


class BullThesis(BaseModel):
    """
    Bull Researcher가 토론 1라운드에서 생성하는 매수/상승 우호 주장.

    역할:
    - 후보 종목 중 가장 매력적인 매수 기회를 가진 종목을 선택해 매수 논거를 제시한다.
    - 자신의 주장에 따르는 리스크도 함께 인정한다.
      - asset: 주장 대상 종목 코드 (반드시 후보 종목 중 하나)
      - recommended_side: "buy" 또는 "hold" (sell 금지)
      - claim: 핵심 주장 요약
      - evidence: 매수 근거 (기술/이벤트/감성/과거 사례 등)
      - risks_acknowledged: 본인 주장에 따르는 리스크
      - confidence: 주장 신뢰도 점수
    """
    asset: str
    recommended_side: Literal["buy", "hold"]
    claim: str = ""
    evidence: list[str] = Field(default_factory=list)
    risks_acknowledged: list[str] = Field(default_factory=list)
    confidence: float = 0.0


class BearThesis(BaseModel):
    """
    Bear Researcher가 토론 1라운드에서 생성하는 매도/보류/리스크 우호 주장.

    역할:
    - Bull Researcher의 주장을 반박하고 리스크 근거를 제시한다.
    - bull_thesis가 비어 있어도 분석 결과만으로 독립적 리스크 분석을 수행한다.
      - asset: 검토 대상 종목 코드
      - recommended_side: "sell" 또는 "hold" (buy 금지)
      - claim: 핵심 주장 요약
      - evidence: 리스크 근거
      - counterpoints_to_bull: Bull 주장에 대한 구체적 반박
      - confidence: 주장 신뢰도 점수
    """
    asset: str
    recommended_side: Literal["sell", "hold"]
    claim: str = ""
    evidence: list[str] = Field(default_factory=list)
    counterpoints_to_bull: list[str] = Field(default_factory=list)
    confidence: float = 0.0


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

    @model_validator(mode="after")
    def validate_trade_params(self) -> "ResearchVerdict":
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
