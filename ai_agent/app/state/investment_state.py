from typing import Any, Literal

from pydantic import BaseModel, Field


class InvestmentAgentState(BaseModel):
    """
    LangGraph 멀티 에이전트 파이프라인에서 공유되는 상태 모델.

    각 Agent는 이 State를 읽고, 자신이 담당하는 필드만 업데이트한다.
    """

    # Analysis Layer output
    market_snapshot: dict[str, Any] = Field(default_factory=dict)
    signals: list[dict[str, Any]] = Field(default_factory=list)
    candidate_assets: list[dict[str, Any]] = Field(default_factory=list)

    # Portfolio / account snapshot
    portfolio_snapshot: dict[str, Any] = Field(default_factory=dict)

    # Memory Agent output
    memory_context: str = ""
    user_context: dict[str, Any] = Field(default_factory=dict)
    policy_context: dict[str, Any] = Field(default_factory=dict)
    history_context: str = ""

    # Reasoning Layer output
    strategy_draft: dict[str, Any] = Field(default_factory=dict)
    critic_feedback: dict[str, Any] = Field(default_factory=dict)
    final_decision: dict[str, Any] = Field(default_factory=dict)

    # Risk Guard output
    risk_check_result: dict[str, Any] = Field(default_factory=dict)
    risk_cleared: bool = False

    # Execution output
    execution_result: dict[str, Any] = Field(default_factory=dict)
    execution_retry_count: int = 0

    # Feedback Layer output
    later_market_data: dict[str, Any] = Field(default_factory=dict)
    postmortem_report: dict[str, Any] = Field(default_factory=dict)

    # Control / Failure handling
    flow_status: Literal[
        "running",
        "hold",
        "blocked",
        "completed",
        "failed",
    ] = "running"
    error_context: dict[str, Any] = Field(default_factory=dict)

    # Optional human approval
    approval_required: bool = False
    approval_result: dict[str, Any] = Field(default_factory=dict)