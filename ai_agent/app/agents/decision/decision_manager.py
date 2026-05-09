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

_PROMPT_PATH = Path(__file__).resolve().parents[2] / "config" / "prompts" / "decision_manager.txt"

_parser = PydanticOutputParser(pydantic_object=FinalDecision)


def decision_manager(state: InvestmentAgentState) -> dict[str, Any]:
    """
    Decision Manager.

    역할:
    - Strategy Team의 ResearchVerdict를 실행 가능한 FinalDecision으로 변환한다.
    - 사이즈/타이밍/시나리오/risk_level/user_message를 함께 결정한다.
    - 출력은 FinalDecision 스키마이며, 후속 Risk Gate가 이를 검증한다.

    실패 시 정책:
    - 출력 파싱 또는 LLM 호출이 실패하면 hold로 강등한다.
    - ResearchVerdict가 hold를 권고했다면 LLM 호출 없이 즉시 hold FinalDecision을 반환한다.
    """

    verdict = state.research_verdict

    if verdict is None:
        return _hold(
            reason="research_verdict가 없어 최종 판단을 보류합니다.",
            asset=None,
            risk_summary=["Strategy Team 결과 누락"],
        )

    if verdict.recommended_side == "hold":
        return _hold(
            reason=verdict.rationale or "Strategy Manager가 hold를 권고했습니다.",
            asset=verdict.asset or None,
            confidence=verdict.confidence,
        )

    chain = load_prompt(str(_PROMPT_PATH)) | get_strategy_llm() | _parser

    debate_state = state.investment_debate_state or {}

    inputs = {
        "research_verdict": to_json(verdict),
        "debate_history": debate_state.get("history") or "(토론 history 없음)",
        "candidate_assets": to_json(state.candidate_assets),
        "analysis_snapshot": to_json(state.analysis_snapshot),
        "portfolio_snapshot": to_json(state.portfolio_snapshot),
        "user_context": to_json(state.user_context),
        "policy_context": to_json(state.policy_context),
        "memory_context": to_json(state.memory_context),
        "history_context": to_json(state.history_context),
        "format_instructions": _parser.get_format_instructions(),
    }

    try:
        final_decision = chain.invoke(inputs)
    except OutputParserException:
        try:
            final_decision = chain.invoke(inputs)
        except OutputParserException as exc:
            return _hold(
                reason="LLM 출력 파싱 2회 실패",
                asset=verdict.asset,
                risk_summary=[str(exc)],
            )
    except Exception as exc:
        return _hold(
            reason="LLM 호출 실패",
            asset=verdict.asset,
            risk_summary=[str(exc)],
        )

    # action="trade"인 경우 실제 주문으로 이어지므로, 주문 필수 필드가 모두 채워졌는지
    # 코드 레벨에서 검증한다. Pydantic 파싱이 통과해도 Optional 필드라 비어 있을 수 있다.
    if final_decision.action == "trade":
        missing = _missing_trade_fields(final_decision)
        if missing:
            return _hold(
                reason=(
                    "Decision Manager가 trade 결정을 생성했지만 "
                    f"필수 주문 필드가 누락되어 보류합니다: {', '.join(missing)}"
                ),
                asset=get_value(final_decision, "asset") or verdict.asset,
                confidence=get_value(final_decision, "confidence"),
                risk_summary=[
                    "trade 결정에 필요한 주문 정보가 불완전합니다.",
                    *(get_value(final_decision, "risk_summary") or []),
                ],
            )

    return {
        "final_decision": final_decision,
        "flow_status": "running" if final_decision.action == "trade" else "hold",
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
                base="조건이 명확해질 때까지 진입하지 않습니다.",
                bear="불확실성이 해소되기 전에는 손실 가능성을 최소화합니다.",
                bull="추가 데이터 확인 후 조건이 개선되면 재검토합니다.",
            ),
            confidence=confidence or 0.0,
            risk_level="low",
            user_message="현재 조건에서는 투자 판단을 보류합니다.",
        ),
        "flow_status": "hold",
    }


def _missing_trade_fields(decision: FinalDecision) -> list[str]:
    """
    trade 결정에 필요한 필수 주문 필드 누락 여부를 확인한다.
    """
    required = ["asset", "side", "order_amount", "target_price", "stop_loss_price"]
    return [f for f in required if get_value(decision, f) is None]
