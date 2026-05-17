from pathlib import Path
from typing import Any

from langchain_core.exceptions import OutputParserException
from langchain_core.output_parsers import PydanticOutputParser

from app.config.llm import get_structured_llm
from app.observability.langsmith_helpers import add_run_metadata
from app.state.investment_state import InvestmentAgentState
from app.state.schemas import ResearchVerdict, StrategyDraft
from app.utils.agent_message import publish_agent_message
from app.utils.json_utils import to_json
from app.utils.prompt_loader import load_prompt

_PROMPT_PATH = Path(__file__).resolve().parents[2] / "config" / "prompts" / "strategy_manager.txt"

_parser = PydanticOutputParser(pydantic_object=ResearchVerdict)


def _format_arguments(history: list[str], side: str) -> str:
    if not history:
        return f"({side} 발언 없음)"
    parts = [f"Round {i}:\n{arg}" for i, arg in enumerate(history, 1)]
    return "\n\n".join(parts)


def strategy_manager(state: InvestmentAgentState) -> dict[str, Any]:
    """
    Strategy Manager (Research Manager / debate facilitator).

    역할:
    - investment_debate_state.history(Bull/Bear 자유 텍스트 토론)를 비판적으로 평가한다.
    - 출력은 ResearchVerdict 스키마(structured)이며, 동일 결정을 StrategyDraft로 변환해
      후속 critic/supervisor 단계 계약을 유지한다.

    실패 시 정책:
    - 출력 파싱 또는 LLM 호출이 실패하면 flow_status="hold"로 강등한다.
    - 후보 외 종목 선택 시에도 hold로 강등한다.
    """

    chain = load_prompt(str(_PROMPT_PATH)) | get_structured_llm() | _parser

    debate_state = state.investment_debate_state or {}
    bull_history: list[str] = debate_state.get("bull_history", [])
    bear_history: list[str] = debate_state.get("bear_history", [])

    signals = state.analysis_snapshot.get("signals", {}) if state.analysis_snapshot else {}

    inputs = {
        "candidate_assets": to_json(state.candidate_assets),
        "signals_technical": to_json(signals.get("technical", {})),
        "signals_fundamental": to_json(signals.get("fundamental", {})),
        "signals_event": to_json(signals.get("event", {})),
        "signals_sentiment": to_json(signals.get("sentiment", {})),
        "portfolio_snapshot": to_json(state.portfolio_snapshot),
        "user_context": to_json(state.user_context),
        "policy_context": to_json(state.policy_context),
        "memory_context": to_json(state.memory_context),
        "history_context": to_json(state.history_context),
        "bull_arguments": _format_arguments(bull_history, "Bull"),
        "bear_arguments": _format_arguments(bear_history, "Bear"),
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
            # 재시도 중 LLM 호출 자체가 실패하면 outer except에 닿지 않으므로 여기서 잡아 hold 강등.
            return _hold("재시도 중 LLM 호출 실패", str(exc))
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

    add_run_metadata({
        "node": "strategy_manager",
        "winning_side": verdict.winning_side,
        "recommended_side": verdict.recommended_side,
        "confidence": verdict.confidence,
    })

    round_count = debate_state.get("round_count", 0)
    publish_agent_message(state, "STRATEGY", round_count * 2, verdict.rationale)

    return {
        "research_verdict": verdict,
        "strategy_draft": _to_strategy_draft(verdict),
    }


def _to_strategy_draft(verdict: ResearchVerdict) -> StrategyDraft:
    """
    ResearchVerdict를 후속 critic/supervisor 단계가 사용하는 StrategyDraft로 변환한다.
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
    """
    Manager 단계 실패 시 안전한 hold 상태로 강등한다.

    critic/supervisor가 strategy_draft=None도 자체 fallback으로 처리하긴 하지만,
    trace 일관성과 사용자 노출용 메시지 보존을 위해 hold verdict/draft를 명시적으로 채운다.
    """
    hold_verdict = ResearchVerdict(
        winning_side="balanced",
        asset="",
        recommended_side="hold",
        rationale=reason,
        confidence=0.0,
    )
    add_run_metadata({
        "node": "strategy_manager",
        "winning_side": hold_verdict.winning_side,
        "recommended_side": hold_verdict.recommended_side,
        "confidence": hold_verdict.confidence,
    })
    return {
        "flow_status": "hold",
        "research_verdict": hold_verdict,
        "strategy_draft": _to_strategy_draft(hold_verdict),
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
