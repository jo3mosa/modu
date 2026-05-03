from typing import Any

from app.state.investment_state import InvestmentAgentState
from app.state.schemas import ExpectedScenario, FinalDecision


def supervisor_agent(state: InvestmentAgentState) -> dict[str, Any]:
    """
    Supervisor Agent.

    역할:
    - Strategy Agent의 전략 초안과 Critic Agent의 피드백을 종합한다.
    - 최종적으로 거래할지, 보류할지 결정한다.
    - 사용자에게 보여줄 판단 사유도 함께 생성한다.

    입력:
    - strategy_draft
    - critic_feedback
    - memory_context
    - history_context

    출력:
    - final_decision
    - flow_status
    """

    draft = state.strategy_draft
    feedback = state.critic_feedback

    # 선행 Agent 결과가 누락된 경우 안전하게 보류 처리
    if draft is None or feedback is None:
        return {
            "final_decision": FinalDecision(
                action="hold",
                reason_summary="strategy_draft 또는 critic_feedback가 없어 판단을 보류합니다.",
                expected_scenario=ExpectedScenario(
                    base="선행 에이전트 결과 누락으로 보류합니다.",
                ),
            ),
            "flow_status": "hold",
        }

    # Strategy Agent가 보류를 권고한 경우
    if draft.side == "hold":
        return {
            "final_decision": FinalDecision(
                action="hold",
                asset=draft.asset,
                reason_summary=draft.reason or "Strategy Agent가 보류를 권고했습니다.",
                expected_scenario=ExpectedScenario(
                    base="전략 초안 단계에서 보류 판단이 내려졌습니다.",
                ),
                confidence=draft.confidence,
                user_message="현재 조건에서는 진입 근거가 충분하지 않아 보류합니다.",
            ),
            "flow_status": "hold",
        }

    # Critic Agent가 승인하지 않은 경우 거래하지 않고 보류
    if not feedback.approved:
        return {
            "final_decision": FinalDecision(
                action="hold",
                asset=draft.asset,
                reason_summary="Critic Agent가 리스크 과다로 보류를 권고했습니다.",
                risk_summary=feedback.comments,
                expected_scenario=ExpectedScenario(
                    base="추가 시장 데이터 확인 전까지 진입을 보류합니다.",
                ),
                confidence=0.0,
                user_message="현재 조건에서는 투자 판단을 보류합니다.",
            ),
            "flow_status": "hold",
        }

    # Strategy가 승인된 경우 최종 거래 결정을 생성
    return {
        "final_decision": FinalDecision(
            action="trade",
            asset=draft.asset,
            side=draft.side,
            order_amount=draft.order_amount,
            target_price=draft.target_price,
            stop_loss_price=draft.stop_loss_price,
            reason_summary=draft.reason,
            risk_summary=feedback.comments,
            expected_scenario=ExpectedScenario(
                base="기술적 반등 흐름이 이어질 경우 목표가 접근 가능성이 있습니다.",
                bear="시장 변동성이 확대되거나 신호가 약화되면 손절가 기준으로 리스크를 제한합니다.",
                bull="거래량 증가와 긍정적 sentiment가 유지되면 분할 매수 전략을 검토할 수 있습니다.",
            ),
            confidence=draft.confidence,
            user_message=f"{draft.asset}에 대해 제한적 매수 전략을 제안합니다.",
        )
    }
