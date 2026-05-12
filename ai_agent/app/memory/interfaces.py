from datetime import datetime
from typing import Any, Literal, Protocol, TypedDict

# ============================================================
# 공통 Enum 타입 정의
# ============================================================
# AI 최종 판단 결과 Enum 정의
    # BUY  : 매수
    # SELL : 매도
    # HOLD : 관망/유지
Decision = Literal["BUY", "SELL", "HOLD"]

# 주문 상태 Enum 정의
    # PENDING   : 주문 생성 후 아직 체결 전
    # FILLED    : 전량 체결 완료
    # CANCELLED : 주문 취소
    # REJECTED  : 거래소/KIS/API 검증 실패로 거부
OrderStatus = Literal["PENDING", "FILLED", "CANCELLED", "REJECTED"]

# Bull/Bear 토론에서 어느 관점이 최종 판단에 더 강하게 반영됐는지 
DebateSide = Literal["BULL", "BEAR", "BALANCED"]

# AI가 예상한 향후 시장 시나리오 유형
ScenarioType = Literal["BASE", "BULL", "BEAR"]

# ============================================================
# Memory Retrieval 결과 타입
# ============================================================
class PastDecision(TypedDict):
    """
    과거 판단을 조회했을 때 사용하는 반환 타입

    - 최근 동일 종목 BUY 판단 조회
    - 유사 signal 기반 과거 실패 사례 조회
    - Bull/Bear 논리 회상
    - Post Mortem 참고    
    """
    ai_judgment_id: int         # AI 판단 레코드 ID
    user_id: int                # 판단을 받은 사용자 ID
    judged_at: datetime         # AI가 판단을 생성한 시각 (timezone-aware)
    stock_code: str             # 종목 코드
    stock_name: str             # 종목명
    sector: str | None          # 업종/섹터
    risk_grade: str | None      # 사용자 투자 성향 등급

    decision: Decision          # AI 최종 판단 결과
    confidence_score: int       # AI 판단 신뢰도 점수 (0~100)
    judgment_reason: str        # AI가 생성한 최종 판단 설명
    key_signals: list[str]      # 판단에 영향을 준 핵심 signal 목록
    
    target_price: int | None    # AI가 판단 당시 예상한 목표가
    stop_loss_price: int | None # AI가 판단 당시 예상한 손절가    
    
    bull_claim: str | None      # 상승 가능성 관점에서 제시한 Bull Agent의 판단 근거
    bear_claim: str | None      # 하락 가능성 관점에서 제시한 Bear Agent의 판단 근거

    order_id: int | None                        # 실제 생성된 주문 ID
    order_amount: int | None                    # 실제 주문 금액
    order_status: OrderStatus | None            # 실제 주문 상태
    realized_profit_loss_rate: float | None     # 최종 실현 손익률


# ============================================================
# 새로운 AI 판단 저장용 타입
# ============================================================

class DecisionLog(TypedDict):
    """
    Decision Agent가 새로운 판단을 저장할 때 사용하는 입력 타입

    PastDecision과 달리:
    - 아직 주문이 생성되지 않았을 수 있음
    - 아직 손익이 확정되지 않았음
    - 현재 시점의 AI 판단 자체를 저장하는 목적    
    """
    user_id: int                # 사용자 ID
    stock_code: str             # 판단 대상 종목 코드
    sector: str | None          # 종목 섹터
    risk_grade: str | None      # 사용자 투자 성향 등급

    decision: Decision          # AI 최종 판단 결과
    order_amount: int           # 주문 금액

    target_price: int | None    # AI가 판단한 목표가
    stop_loss_price: int | None # AI가 판단한 손절가

    judgment_reason: str        # AI 최종 판단 설명
    key_signals: list[str]      # 핵심 signal 목록
    confidence_score: int       # AI 판단 신뢰도 점수 (0~100)

    bull_claim: str | None      # 상승 가능성 관점에서 제시한 Bull Agent의 판단 근거
    bear_claim: str | None      # 하락 가능성 관점에서 제시한 Bear Agent의 판단 근거

    winning_side: DebateSide | None       # Bull/Bear/Balanced 중 최종 판단에 더 반영된 관점
    expected_scenario: ScenarioType | None # AI가 예상한 향후 시나리오

    indicators_snapshot: dict[str, Any]   # 판단 당시 지표 스냅샷 (analysis_snapshot.signals 원본)


# ============================================================
# Post Mortem 저장용 타입
# ============================================================

class PostMortemRecord(TypedDict):
    """
    Post Mortem Agent가 생성한 사후 복기 데이터
    """
    ai_judgment_id: int                 # 어떤 AI 판단에 대한 복기인지 연결
    trade_pnl_record_id: int | None     # 거래 손익 기록 ID
    entry_timing_assessment: str        # 진입 시점 평가
    exit_rule_assessment: str           # 익절/손절 전략 평가
    risk_prediction_accuracy: str       # 리스크 예측 정확도 평가
    missed_signals: list[str]           # 당시 놓쳤던 signal 목록
    lessons: list[str]                  # 다음 판단에 반영할 교훈
    summary: str                        # 전체 복기 요약


# ============================================================
# Memory Storage 추상 인터페이스
# ============================================================

class MemoryStore(Protocol):
    """
    Memory Layer 저장소 추상 인터페이스

    Agent는 이 Protocol에만 의존하고,
    실제 저장소 구현은 DBStore / WikiStore / HybridStore 등으로 교체 가능
    
    key_signals:
        Analysis Layer, Agent, DB가 같은 어휘를 사용해야 한다.
        예: "RSI_OVERSOLD", "GOLDEN_CROSS", "VOLUME_SPIKE"
        실제 enum 목록은 Analysis Layer codebook에서 별도로 합의한다.

    매칭 조건:
        - stock_codes 내부 값끼리는 OR 매칭
        - sectors 내부 값끼리는 OR 매칭
        - key_signals 내부 값끼리는 OR 매칭
        - stock_codes / sectors / key_signals 그룹 간에는 AND 매칭을 기본으로 한다.

        예:
        stock_codes=["005930", "000660"]
        sectors=["반도체"]
        key_signals=["RSI_OVERSOLD", "VOLUME_SPIKE"]

        의미:
        (stock_code가 005930 또는 000660)
        AND (sector가 반도체)
        AND (key_signals 중 RSI_OVERSOLD 또는 VOLUME_SPIKE 포함)
    """

    # 최근 AI 판단 사례 조회
    def get_recent_decisions(
        self,
        user_id: int,
        stock_codes: list[str],
        sectors: list[str],
        key_signals: list[str],
        limit: int = 10,
        days: int = 30,
    ) -> list[PastDecision]: ...

    # 현재 상황과 유사한 과거 판단 조회
    def get_similar_decisions(
        self,
        user_id: int,
        stock_codes: list[str],
        sectors: list[str],
        key_signals: list[str],
        limit: int = 5,
        days: int = 30,
        only_loss: bool = False,
    ) -> list[PastDecision]: ...

    # 새로운 AI 판단 저장
    def store_decision(
        self,
        log: DecisionLog,
    ) -> int: ...

    # 사후 복기 데이터 저장
    def store_postmortem(
        self,
        report: PostMortemRecord,
    ) -> int: ...