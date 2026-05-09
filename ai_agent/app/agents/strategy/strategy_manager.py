from pathlib import Path
from typing import Any

from langchain_core.exceptions import OutputParserException
from langchain_core.output_parsers import PydanticOutputParser

from app.config.llm import get_strategy_llm
from app.state.investment_state import InvestmentAgentState
from app.state.schemas import ResearchVerdict, StrategyDraft
from app.utils.json_utils import to_json
from app.utils.prompt_loader import load_prompt

_PROMPT_PATH = Path(__file__).resolve().parents[2] / "config" / "prompts" / "strategy_manager.txt"

_parser = PydanticOutputParser(pydantic_object=ResearchVerdict)


def strategy_manager(state: InvestmentAgentState) -> dict[str, Any]:
    """
    Strategy Manager (Research Manager).

    역할:
    - Bull Researcher와 Bear Researcher의 1라운드 토론을 종합해 최종 판결을 내린다.
    - 출력은 ResearchVerdict 스키마를 따르며, 동일 결정을 StrategyDraft로 변환해
      후속 critic/supervisor 단계에 전달한다.

    실패 시 정책:
    - 출력 파싱 또는 LLM 호출이 실패하면 flow_status="hold"로 강등한다.
    - 후보 외 종목 선택 시에도 hold로 강등한다.
    """

    chain = load_prompt(str(_PROMPT_PATH)) | get_strategy_llm() | _parser

    inputs = {
        "bull_thesis": to_json(state.bull_thesis),
        "bear_thesis": to_json(state.bear_thesis),
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
        verdict = chain.invoke(inputs)
    except OutputParserException:
        try:
            verdict = chain.invoke(inputs)
        except OutputParserException as exc:
            return _hold("LLM 출력 파싱 2회 실패", str(exc))
    except Exception as exc:
        return _hold("LLM 호출 실패", str(exc))

    valid_codes = _candidate_codes(state.candidate_assets)
    if (
        verdict.recommended_side != "hold"
        and (not valid_codes or verdict.asset not in valid_codes)
    ):
        return _hold(
            "후보 외 종목 선택",
            f"Strategy Manager가 후보 목록에 없는 종목을 선택했습니다: {verdict.asset}",
        )

    return {
        "research_verdict": verdict,
        "strategy_draft": _to_strategy_draft(verdict),
    }


def _to_strategy_draft(verdict: ResearchVerdict) -> StrategyDraft:
    """
    ResearchVerdict를 후속 critic/supervisor 단계가 사용하는 StrategyDraft로 변환한다.

    StrategyDraft는 기존 그래프 하위 노드 계약을 깨지 않기 위해 그대로 유지한다.
    """

    if verdict.recommended_side == "hold":
        return StrategyDraft(
            asset=verdict.asset or "",
            side="hold",
            order_amount=0,
            target_price=None,
            stop_loss_price=None,
            reason=verdict.rationale,
            confidence=verdict.confidence,
        )

    return StrategyDraft(
        asset=verdict.asset,
        side=verdict.recommended_side,
        order_amount=verdict.order_amount,
        target_price=verdict.target_price,
        stop_loss_price=verdict.stop_loss_price,
        reason=verdict.rationale,
        confidence=verdict.confidence,
    )


def _hold(reason: str, detail: str) -> dict[str, Any]:
    return {
        "flow_status": "hold",
        "error_context": {
            "agent": "strategy_manager",
            "reason": reason,
            "detail": detail,
        },
    }


def _candidate_codes(candidate_assets: list[dict[str, Any]]) -> set[str]:
    return {
        asset.get("stock_code") or asset.get("ticker")
        for asset in candidate_assets
        if asset.get("stock_code") or asset.get("ticker")
    }
