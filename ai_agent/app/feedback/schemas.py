"""
Feedback(회고) 도메인 이벤트 / LLM 출력 스키마.

- TradeSettledEvent: backend가 매수→매도 cycle 종료(PnL 확정) 시점에 발행하는 Kafka 이벤트.
  ai_agent feedback consumer가 받아 회고 파이프라인을 실행한다.
- PostMortemReflection: post_mortem_agent의 LLM 구조화 출력.
  pipeline이 이를 PostMortemRecord(memory.interfaces)로 변환해 DB에 저장한다.
"""
from datetime import datetime
from typing import Literal
from uuid import uuid4

from pydantic import BaseModel, Field


class TradeSettledEvent(BaseModel):
    """
    backend가 발행하는 거래 청산 이벤트.

    페이로드 합의 사항:
    - raw_return: 절대 수익률 (예: 0.052 = 5.2%, -0.034 = -3.4%)
    - alpha_return: 시장 벤치마크(KOSPI/KOSDAQ) 대비 알파 — backend가 KIS 시세에서 계산
    - holding_days: 매수 체결일 ~ 매도 체결일 거래일 수
    """

    event_id: str = Field(default_factory=lambda: f"trade_settled_{uuid4()}")
    event_type: Literal["TRADE_SETTLED"] = "TRADE_SETTLED"
    timestamp: datetime = Field(..., description="이벤트 발생 시각")

    user_id: int = Field(..., description="거래 사용자 ID")
    ai_judgment_id: int = Field(..., description="회고 대상이 되는 ai_judgments.id")
    trade_pnl_record_id: int | None = Field(
        default=None,
        description="trade_pnl_records.id (선택)",
    )

    raw_return: float = Field(..., description="절대 수익률. 예: 0.052")
    alpha_return: float = Field(..., description="벤치마크 대비 알파. 예: 0.021")
    holding_days: int = Field(..., gt=0, description="매수→매도 보유 거래일 수")


class PostMortemReflection(BaseModel):
    """
    post_mortem_agent의 LLM 구조화 출력.

    이 객체는 그 자체로 DB에 저장되지 않고, pipeline이 ai_judgment_id 등 외부 식별자와
    합쳐 PostMortemRecord(TypedDict)로 변환해 store_postmortem에 전달한다.

    필드 분할 원칙:
    - 각 텍스트 필드는 1~2문장. 모호한 표현 금지.
    - missed_signals/lessons는 최대 3개. 다음 결정에 적용 가능한 구체적 항목만.
    - summary는 200자 이내. 회상 시 누적 토큰 부풀림을 막기 위함.
    """

    entry_timing_assessment: str = Field(
        ...,
        description="매수 진입 시점 평가 (1~2문장)",
    )
    exit_rule_assessment: str = Field(
        ...,
        description="익절/손절 기준 적절성 (1~2문장)",
    )
    risk_prediction_accuracy: str = Field(
        ...,
        description="당시 평가한 리스크 수준과 실제 결과의 정합성 (1~2문장)",
    )
    missed_signals: list[str] = Field(
        default_factory=list,
        description="당시 놓친 핵심 시그널 (최대 3개)",
    )
    lessons: list[str] = Field(
        default_factory=list,
        description="다음 유사 결정에 적용할 구체적 교훈 (1~3개)",
    )
    summary: str = Field(
        ...,
        description="전체 회고 압축 (200자 이내)",
    )
