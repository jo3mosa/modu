from typing import Literal, Protocol, TypedDict

# 공통 Enum 타입 정의
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

# Memory Retrieval 결과 타입
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
    judged_at: str              # AI가 판단을 생성한 시각
    stock_code: str             # 종목 코드
    stock_name: str             # 종목명
    sector: str | None          # 업종/섹터
    risk_grade: str | None      # 사용자 투자 성향 등급

    decision: Decision          # AI 최종 판단 결과
    confidence_score: int       # AI 판단 신뢰도 점수
    judgment_reason: str        # AI가 생성한 최종 판단 설명
    key_signals: list[str]      # 판단에 영향을 준 핵심 signal 목록
    bull_claim: str | None      # Bull Agent의 매수 논리 요약
    bear_claim: str | None      # Bear Agent의 매도 논리 요약

    order_id: int | None                        # 실제 생성된 주문 ID
    order_status: OrderStatus | None            # 실제 주문 상태
    realized_profit_loss_rate: float | None     # 최종 실현 손익률


# 새로운 AI 판단 저장용 타입
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

    judgment_reason: str        # AI 최종 판단 설명
    key_signals: list[str]      # 핵심 signal 목록
    confidence_score: int       # AI 판단 신뢰도 점수
    bull_claim: str | None      # Bull Agent 주장 요약
    bear_claim: str | None      # Bear Agent 주장 요약


# Post Mortem 저장용 타입
class PostMortemRecord(TypedDict):
    """
    Post Mortem Agent가 생성한 사후 복기 데이터
    """
    ai_judgment_id: int                 # 어떤 AI 판단에 대한 복기인지 연결
    trade_pnl_record_id: int | None     # 거래 손익 기록 ID
    entry_timing_assessment: str        # 진입 시점 평가
    target_price_assessment: str        # 익절/손절 전략 평가
    risk_prediction_accuracy: str       # 리스크 예측 정확도 평가
    missed_signals: list[str]           # 당시 놓쳤던 signal 목록
    lessons: list[str]                  # 다음 판단에 반영할 교훈
    summary: str                        # 전체 복기 요약


# Memory Storage 추상 인터페이스
class MemoryStore(Protocol):
    """
    Memory Layer 저장소 추상 인터페이스
    """

    # 최근 AI 판단 사례 조회
    def get_recent_decisions(
        self,
        user_id: int,
        stock_codes: list[str],
        sectors: list[str],
        key_signals: list[str],
        limit: int = 10,
    ) -> list[PastDecision]: ...

    # 현재 상황과 유사한 과거 판단 조회
    def get_similar_decisions(
        self,
        user_id: int,
        stock_codes: list[str],
        sectors: list[str],
        key_signals: list[str],
        limit: int = 5,
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