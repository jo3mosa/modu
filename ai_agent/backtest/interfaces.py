"""AI 팀이 채울 확장 포인트 — Protocol 로 시그니처만 정의.

DA 가 제공하는 인프라(event_loop, signal_generator) 는 이 프로토콜을 사용해
의사결정·가상 사용자·체결을 호출한다. AI 팀은 이 프로토콜을 구현한 함수만
주입하면 백테스트가 즉시 돌아간다.

설계 원칙:
  - 입력은 가능한 한 dict — AI 팀이 자유롭게 새 필드 추가 가능
  - dataclass 는 출력 Trigger / Decision / Fill 처럼 DA 가 책임지는 자료구조에만 사용
  - 프로토콜이라 typing.runtime_checkable 없이도 duck-typing 으로 충분
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from typing import Any, Optional, Protocol


# ─── DA 가 만들어 AI 팀에게 전달하는 자료구조 ────────────────────────────────

@dataclass(frozen=True)
class Trigger:
    """signal_generator 가 한 시점 × 한 종목에 대해 만들어내는 트리거.

    AI 팀이 의사결정에 필요한 모든 입력을 한 곳에 모았다. 같은 종목에 여러 룰이
    동시 발화하면 rule_ids 에 여러 개가 들어간다 (analysis_server engine 의
    detect() 가 같은 사이클에 multi-rule 발화하는 동작과 정합).
    """
    as_of_date: date              # 트리거 발생 EOD 기준 영업일
    stock_code: str
    rule_ids: list[str]           # 발화한 detection_engine 룰 (RULES dict 키)
    rule_reasons: list[str]       # rule_ids 의 한국어 사유 (RULE_REASONS)
    # 4 source signal payload — analysis_server signal_builder 와 동일 모양.
    # AI 팀이 의사결정 컨텍스트로 직접 들여다볼 수 있게 그대로 전달.
    technical: Optional[dict]
    fundamental: Optional[dict]
    event: Optional[dict]
    sentiment: Optional[dict]
    # 가격 메타 — 체결 시뮬레이션에 필요.
    close_price: Optional[float]  # as_of_date 종가 (의사결정 기준 가격)


@dataclass(frozen=True)
class Decision:
    """AI 팀 의사결정 결과 — ai_agent FinalDecision 과 동일 schema 지향.

    체결 시뮬레이터(또는 AI 팀의 PortfolioFn) 가 이 dataclass 를 받아
    Fill 을 산출한다. 모든 가격 필드는 KRW 단위.
    """
    action: str                       # "buy" | "sell" | "hold"
    order_amount: Optional[int] = None      # 주문 주식 수
    target_price: Optional[float] = None    # limit price (None=시장가)
    stop_loss_price: Optional[float] = None
    confidence: Optional[float] = None      # 0.0 ~ 1.0
    reasoning: Optional[str] = None         # 자유 형식 (LLM rationale 등)
    extras: dict = field(default_factory=dict)   # AI 팀 자유 메타


@dataclass(frozen=True)
class Fill:
    """체결 시뮬레이터의 결과 — PortfolioFn 가 의사결정을 처리하고 반환.

    백테스트는 이를 그대로 output.py 에 기록만 함. 손익 계산은 AI 팀이 자유롭게.
    """
    fill_date: date
    fill_price: Optional[float]    # None = 미체결 (호가 미달 등)
    filled_amount: Optional[int]   # 부분체결 가능
    fee: float = 0.0
    tax: float = 0.0
    notes: Optional[str] = None    # "next_day_open" / "limit_unfilled" 등


# ─── AI 팀이 구현할 프로토콜 ─────────────────────────────────────────────────

class DecisionFn(Protocol):
    """트리거 → 의사결정. event_loop 가 매 트리거마다 호출.

    Args:
        trigger:  DA 가 생성한 한 트리거 (시점·종목·룰·signal 4종)
        user_context: AI 팀이 build_user_context_fn 로 만든 가상 사용자 컨텍스트.
                      형식 자유 — 정책·잔고·위험허용도 등.
        portfolio_snapshot: 현 시점 portfolio 상태. PortfolioFn 가 관리하는 객체.

    Returns:
        Decision. action="hold" 면 체결 단계 skip.
    """
    def __call__(
        self,
        trigger: Trigger,
        user_context: dict,
        portfolio_snapshot: Any,
    ) -> Decision: ...


class UserContextFn(Protocol):
    """가상 사용자 컨텍스트 — 시점별 사용자 정책·잔고 등.

    여러 가상 사용자(공격형/안정형/배당형 등) 를 비교하려면 동일 시그니처로
    각각 다른 함수를 만들어 event_loop 에 리스트로 주입.
    """
    def __call__(self, day: date, user_id: str) -> dict: ...


class PortfolioFn(Protocol):
    """가상 포트폴리오 관리 — 사용자별 1 인스턴스 가정.

    event_loop 는 의사결정 발생 시 execute(decision, market_state) 를 호출하고,
    EOD 마다 mark_to_market(close_prices) 를 호출해 일별 자산 곡선을 기록한다.
    구현은 AI 팀 자유 — 단순 cash+holdings dict 부터 정교한 호가 시뮬레이터까지.
    """
    user_id: str

    def snapshot(self) -> Any:
        """현재 상태 read-only 뷰 — DecisionFn 에 전달."""
        ...

    def execute(self, trigger: Trigger, decision: Decision,
                market_state: dict) -> Fill:
        """의사결정 체결. market_state = {next_open, next_high, next_low, ...}."""
        ...

    def mark_to_market(self, day: date, close_prices: dict[str, float]) -> dict:
        """EOD 평가 — 일별 자산 곡선·미실현손익 기록. metrics 모듈이 소비."""
        ...

    # 선택 구현 — 보유 포지션의 stop_loss / target_price 도달 평가.
    # event_loop는 hasattr 체크 후 있을 때만 호출하므로 미구현이어도 동작.
    # ohlcv_rows = {stock_code: {open, high, low, close, volume}}.
    # 발생한 Fill 리스트 반환 (event_loop가 JSONL 레코드로 기록).
    #
    # def evaluate_open_positions(self, day: date, ohlcv_rows: dict[str, dict]) -> list[Fill]: ...
