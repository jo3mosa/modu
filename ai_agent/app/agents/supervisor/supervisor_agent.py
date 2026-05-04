from pathlib import Path
from typing import Any

from langchain_core.exceptions import OutputParserException
from langchain_core.output_parsers import PydanticOutputParser

from app.config.llm import get_strategy_llm
from app.state.investment_state import InvestmentAgentState
from app.state.schemas import ExpectedScenario, FinalDecision
from app.utils.json_utils import to_json
from app.utils.object_utils import get_value
from app.utils.prompt_loader import load_prompt


_PROMPT_PATH = Path(__file__).resolve().parents[2] / "config" / "prompts" / "supervisor_agent.txt"

_parser = PydanticOutputParser(pydantic_object=FinalDecision)


def supervisor_agent(state: InvestmentAgentState) -> dict[str, Any]:
    """
    Supervisor Agent.

    역할:
    - Strategy Agent의 전략 초안, Critic Agent의 리스크 검토 결과,
      Memory Agent의 사용자/과거 문맥을 종합한다.
    - 최종 투자 결정을 FinalDecision 스키마로 생성한다.
    - 선행 Agent 결과가 없거나 LLM 처리에 실패한 경우 안전하게 hold 처리한다.
    """

    draft = state.strategy_draft
    feedback = state.critic_feedback

    if draft is None:
        return _hold(
            reason="strategy_draft가 없어 최종 판단을 보류합니다.",
            asset=None,
            risk_summary=["Strategy Agent 결과가 누락되었습니다."],
        )

    if feedback is None:
        return _hold(
            reason="critic_feedback이 없어 최종 판단을 보류합니다.",
            asset=get_value(draft, "asset"),
            risk_summary=["Critic Agent 결과가 누락되었습니다."],
        )

    inputs = {
        "strategy_draft": to_json(draft),
        "critic_feedback": to_json(feedback),
        "memory_context": to_json(state.memory_context),
        "history_context": to_json(state.history_context),
        "user_context": to_json(state.user_context),
        "policy_context": to_json(state.policy_context),
        "portfolio_snapshot": to_json(state.portfolio_snapshot),
        "format_instructions": _parser.get_format_instructions(),
    }

    try:
        chain = load_prompt(str(_PROMPT_PATH)) | get_strategy_llm() | _parser

        try:
            final_decision = chain.invoke(inputs)

        except OutputParserException:
            final_decision = chain.invoke(inputs)

    except OutputParserException:
        return _hold(
            reason="Supervisor Agent의 LLM 출력 파싱이 2회 실패하여 판단을 보류합니다.",
            asset=get_value(draft, "asset"),
            risk_summary=get_value(feedback, "comments") or [],
        )

    except Exception:
        return _hold(
            reason="Supervisor Agent의 LLM 호출 또는 체인 생성에 실패하여 판단을 보류합니다.",
            asset=get_value(draft, "asset"),
            risk_summary=get_value(feedback, "comments") or [],
        )

    # action="trade"인 경우 실제 주문으로 이어질 수 있으므로
    # 주문 실행에 필요한 필수 필드가 모두 채워졌는지 코드 레벨에서 검증한다.
    #
    # FinalDecision의 주문 관련 필드는 Optional일 수 있기 때문에,
    # Pydantic 파싱이 성공해도 asset, side, order_amount 등이 비어 있을 수 있다.
    # 이 상태로 flow_status="trade"를 반환하면 Risk Guard / Executor 단계로
    # 불완전한 주문 정보가 전달될 수 있으므로 hold로 강등한다.
    if final_decision.action == "trade":
        missing_fields = _get_missing_trade_fields(final_decision)

        if missing_fields:
            return _hold(
                reason=(
                    "Supervisor Agent가 trade 결정을 생성했지만 "
                    f"필수 주문 필드가 누락되어 판단을 보류합니다: {', '.join(missing_fields)}"
                ),
                asset=get_value(final_decision, "asset") or get_value(draft, "asset"),
                confidence=get_value(final_decision, "confidence"),
                risk_summary=[
                    "trade 결정에 필요한 주문 정보가 불완전합니다.",
                    *(get_value(final_decision, "risk_summary") or get_value(feedback, "comments") or []),
                ],
            )

    return {
        "final_decision": final_decision,
        "flow_status": "trade" if final_decision.action == "trade" else "hold",
    }


def _hold(
    reason: str,
    asset: str | None = None,
    confidence: float | None = 0.0,
    risk_summary: list[str] | None = None,
) -> dict[str, Any]:
    return {
        "final_decision": FinalDecision(
            action="hold",
            asset=asset,
            reason_summary=reason,
            risk_summary=risk_summary or [],
            expected_scenario=ExpectedScenario(
                base="선행 판단 결과가 불충분하거나 리스크 검토를 통과하지 못해 관망합니다.",
                bear="불확실성이 해소되기 전까지 진입하지 않아 손실 확대 가능성을 제한합니다.",
                bull="추가 데이터 확인 후 조건이 개선되면 재검토할 수 있습니다.",
            ),
            confidence=confidence,
            user_message="현재 조건에서는 투자 판단을 보류합니다.",
        ),
        "flow_status": "hold",
    }


def _get_missing_trade_fields(final_decision: FinalDecision) -> list[str]:
    """
    trade 결정에 필요한 필수 주문 필드 누락 여부를 확인한다.
    """

    required_fields = [
        "asset",
        "side",
        "order_amount",
        "target_price",
        "stop_loss_price",
    ]

    missing_fields: list[str] = []

    for field in required_fields:
        value = get_value(final_decision, field)

        if value is None:
            missing_fields.append(field)

    return missing_fields