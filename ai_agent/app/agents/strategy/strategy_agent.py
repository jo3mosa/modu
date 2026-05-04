from pathlib import Path
from typing import Any

from langchain_core.exceptions import OutputParserException
from langchain_core.output_parsers import PydanticOutputParser

from app.config.llm import get_strategy_llm
from app.state.investment_state import InvestmentAgentState
from app.state.schemas import StrategyDraft
from app.utils.json_utils import to_json
from app.utils.prompt_loader import load_prompt

_PROMPT_PATH = Path(__file__).resolve().parents[2] / "config" / "prompts" / "strategy_agent.txt"

_parser = PydanticOutputParser(pydantic_object=StrategyDraft)


def strategy_agent(state: InvestmentAgentState) -> dict[str, Any]:
    """
    Strategy Agent.

    역할:
    - Analysis Layer 결과와 Memory Agent 문맥을 기반으로 LLM이 투자 전략 초안을 생성한다.
    - 출력은 반드시 StrategyDraft 스키마를 따른다.

    입력:
    - analysis_snapshot
    - candidate_assets
    - portfolio_snapshot
    - memory_context
    - user_context
    - policy_context
    - history_context

    출력:
    - strategy_draft
    """

    chain = load_prompt(str(_PROMPT_PATH)) | get_strategy_llm() | _parser

    inputs = {
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
        strategy_draft = chain.invoke(inputs)
    except OutputParserException:
        # 파싱 실패는 1회 재시도
        try:
            strategy_draft = chain.invoke(inputs)
        except OutputParserException as exc:
            return _hold("LLM 출력 파싱 2회 실패", str(exc))
    except Exception as exc:
        # 네트워크, 인증, 레이트리밋 등 LLM 호출 자체 실패 — 재시도 없이 즉시 hold
        return _hold("LLM 호출 실패", str(exc))

    # 후보 종목 외 asset 선택 여부 검증 (side=hold일 때는 실제 주문 없으므로 생략)
    if strategy_draft.side != "hold":
        valid_codes = {
            asset.get("stock_code") or asset.get("ticker")
            for asset in state.candidate_assets
            if asset.get("stock_code") or asset.get("ticker")
        }
        if not valid_codes or strategy_draft.asset not in valid_codes:
            return _hold(
                "후보 외 종목 선택",
                f"LLM이 후보 목록에 없는 종목을 선택했습니다: {strategy_draft.asset}",
            )

    return {"strategy_draft": strategy_draft}


def _hold(reason: str, detail: str) -> dict[str, Any]:
    return {
        "flow_status": "hold",
        "error_context": {
            "agent": "strategy_agent",
            "reason": reason,
            "detail": detail,
        },
    }
