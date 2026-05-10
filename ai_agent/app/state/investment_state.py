from typing import Any, Literal

from pydantic import BaseModel, Field

from app.state.schemas import (
    FinalDecision,
    ResearchVerdict,
    StrategyDraft,
)


class InvestmentAgentState(BaseModel):
    """
    LangGraph 멀티 에이전트 파이프라인에서 공유되는 상태 모델.

    각 Agent는 이 State를 읽고, 자신이 담당하는 필드만 업데이트한다.
    """

    # 그래프 실행 주체 사용자 ID
    user_id: int | None = None

    # ==============================
    # 1. Analysis Layer output
    # ==============================

    # 시장 전체 상태 요약 (KOSPI/KOSDAQ 등 지수 변화율, 장 운영 여부 정도)
    market_snapshot: dict[str, Any] = Field(default_factory=dict)
    
    # 분석 서버에서 전달받은 종목 단위 분석 결과 (Kafka 메시지 포맷을 그대로 담는 영역)
    # {"stock_code": "005930", "timestamp": "...", "signals": {"technical": {...}, ...}}
    analysis_snapshot: dict[str, Any] = Field(default_factory=dict)
    
    # 판단 대상 후보 종목 목록
    # # Strategy Agent는 이 목록에서 투자 후보를 선택함
    candidate_assets: list[dict[str, Any]] = Field(default_factory=list)

    # ==============================
    # 2. Portfolio / account snapshot
    # ==============================
    
    # 현재 계좌 상태 스냅샷
    # 예: 보유 종목, 현금 잔고, 총 자산

    # MVP 정책:
    # - Strategy Agent와 Risk Guard가 모두 이 값을 참조한다.
    # - Risk Guard가 broker API를 직접 호출하지 않는다.
    # - 즉, 외부 계좌 조회는 그래프 실행 전에 끝났다고 가정한다.
    portfolio_snapshot: dict[str, Any] = Field(default_factory=dict)

    # ==============================
    # 3. Memory Agent output
    # ==============================

    # Memory Agent가 만든 요약 문맥
    # 과거 유사 거래, 사용자 성향, 정책 등을 LLM 입력용으로 압축한 값
    memory_context: dict[str, Any] = Field(default_factory=dict)

    # 사용자 투자 성향 및 리스크 설정
    # 예: risk_level, max_order_amount, stop_loss_rate
    user_context: dict[str, Any] = Field(default_factory=dict)

    # 서비스 공통 투자 정책
    # 예: 자동매매 허용 여부, kill-switch 설정 등
    policy_context: dict[str, Any] = Field(default_factory=dict)

    # 과거 거래 복기에서 도출된 패턴/교훈
    history_context: dict[str, Any] = Field(default_factory=dict)

    # ==============================
    # 4. Reasoning Layer output
    # ==============================

    # Bull/Bear 토론 누적 상태 (TradingAgents 레포의 investment_debate_state와 동일 구조).
    # - history: 전체 토론 누적 텍스트 (Bull/Bear 모두)
    # - bull_history: Bull 발언만 누적
    # - bear_history: Bear 발언만 누적
    # - current_response: 직전 발언 (다음 화자가 반박 대상으로 사용)
    # - count: 라운드 카운트 (1라운드 고정 환경에서도 향후 확장을 위해 보존)
    investment_debate_state: dict[str, Any] = Field(default_factory=dict)

    # Strategy Manager(Research Manager)가 토론을 종합해 내린 판결
    research_verdict: ResearchVerdict | None = None

    # research_verdict를 후속 decision_manager가 입력으로 받는 전략 초안
    strategy_draft: StrategyDraft | None = None

    # Decision Manager가 생성한 최종 투자 결정 (사이즈/타이밍/시나리오/risk_level 포함)
    final_decision: FinalDecision | None = None

    # ==============================
    # 5. Risk Guard output
    # ==============================
    
    # Risk Guard의 상세 검증 결과
    # 예: {"status": "passed", "reason": "..."}
    risk_check_result: dict[str, Any] = Field(default_factory=dict)
    
    # 실제 주문 실행 가능 여부
    # True일 때만 executor로 이동
    risk_cleared: bool = False

    # ==============================
    # 6. Execution output
    # ==============================

    # 주문 실행 결과
    # 지금은 mock 주문 결과, 나중에는 한국투자증권 API 결과가 들어갈 예정
    execution_result: dict[str, Any] = Field(default_factory=dict)
    
    # 주문 실행 재시도 횟수
    execution_retry_count: int = 0

    # ==============================
    # 7. Feedback Layer output
    # ==============================

    # 매도 체결 후 별도 스케줄러 또는 Feedback Graph가 주입하는 시장 데이터
    # Decision Graph 실행 중에는 기본적으로 빈 dict
    later_market_data: dict[str, Any] = Field(default_factory=dict)
    
    # Post Mortem Agent가 생성하는 사후 분석 리포트
    postmortem_report: dict[str, Any] = Field(default_factory=dict)

    # ==============================
    # 8. Control / Failure handling
    # ==============================

    # 전체 그래프 실행 상태
    flow_status: Literal[
        "running",
        "hold",
        "blocked",
        "completed",
        "failed",
    ] = "running"
    
    # 오류 발생 시 원인 기록
    # 예: LLM 파싱 실패, API 오류 등
    error_context: dict[str, Any] = Field(default_factory=dict)

    # ==============================
    # 9. Optional human approval
    # ==============================

    # 사용자 승인 결과 (백엔드가 고위험 조건 판단 후 주입)
    approval_result: dict[str, Any] = Field(default_factory=dict)