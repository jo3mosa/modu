from pathlib import Path
from typing import Any

from langchain_core.exceptions import OutputParserException
from langchain_core.output_parsers import PydanticOutputParser

from app.config.llm import get_strategy_llm
from app.state.investment_state import InvestmentAgentState
from app.state.schemas import BearThesis
from app.utils.json_utils import to_json
from app.utils.prompt_loader import load_prompt

_PROMPT_PATH = Path(__file__).resolve().parents[2] / "config" / "prompts" / "bear_researcher.txt"

_parser = PydanticOutputParser(pydantic_object=BearThesis)


def bear_researcher(state: InvestmentAgentState) -> dict[str, Any]:
    """
    Bear Researcher.

    역할:
    - Bull Researcher의 주장을 반박하고 리스크 우호 주장을 생성한다.
    - bull_thesis가 비어 있어도 분석 결과만으로 독립 리스크 분석을 수행한다.
    - 출력은 BearThesis 스키마를 따른다.

    실패 시 정책:
    - LLM 출력 파싱 실패 또는 호출 실패 시 hold 권고로 fallback한다.
    - 후보 외 종목 선택 시 fallback thesis로 강등한다.
    """

    chain = load_prompt(str(_PROMPT_PATH)) | get_strategy_llm() | _parser

    inputs = {
        "bull_thesis": to_json(state.bull_thesis),
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
        thesis = chain.invoke(inputs)
    except OutputParserException:
        try:
            thesis = chain.invoke(inputs)
        except OutputParserException as exc:
            return _fallback_thesis("LLM 출력 파싱 2회 실패", str(exc), state)
    except Exception as exc:
        return _fallback_thesis("LLM 호출 실패", str(exc), state)

    valid_codes = _candidate_codes(state.candidate_assets)
    if valid_codes and thesis.asset not in valid_codes:
        return _fallback_thesis(
            "후보 외 종목 선택",
            f"Bear Researcher가 후보 목록에 없는 종목을 선택했습니다: {thesis.asset}",
            state,
        )

    return {"bear_thesis": thesis}


def _fallback_thesis(reason: str, detail: str, state: InvestmentAgentState) -> dict[str, Any]:
    """
    Bear Researcher가 정상 분석을 완료하지 못했을 때 안전한 hold thesis를 반환한다.

    Strategy Manager는 비어있다시피 한 양측 thesis를 종합해 보수적으로 판단해야 한다.
    """

    valid_codes = _candidate_codes(state.candidate_assets)
    asset = next(iter(valid_codes), "") if valid_codes else ""

    return {
        "bear_thesis": BearThesis(
            asset=asset,
            recommended_side="hold",
            claim="Bear Researcher 정상 분석 실패로 hold를 권고합니다.",
            evidence=[],
            counterpoints_to_bull=[f"{reason}: {detail}"],
            confidence=0.0,
        )
    }


def _candidate_codes(candidate_assets: list[dict[str, Any]]) -> set[str]:
    return {
        asset.get("stock_code") or asset.get("ticker")
        for asset in candidate_assets
        if asset.get("stock_code") or asset.get("ticker")
    }
