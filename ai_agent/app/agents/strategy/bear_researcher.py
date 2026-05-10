from pathlib import Path
from typing import Any

from app.config.llm import get_strategy_llm
from app.state.investment_state import InvestmentAgentState
from app.utils.json_utils import to_json
from app.utils.prompt_loader import load_prompt

_PROMPT_PATH = Path(__file__).resolve().parents[2] / "config" / "prompts" / "bear_researcher.txt"


def bear_researcher(state: InvestmentAgentState) -> dict[str, Any]:
    """
    Bear Researcher.

    역할:
    - 매도/리스크 입장에서 자유 텍스트로 토론 발언을 생성한다.
    - 직전 Bull 발언(current_response)을 받아 직접 반박한다.
    - investment_debate_state.history에 "Bear Analyst:" 화자 prefix와 함께 누적한다.

    실패 시 정책:
    - LLM 호출이 실패하면 fallback 발언을 history에 남기고 다음 노드로 진행한다.
    """

    chain = load_prompt(str(_PROMPT_PATH)) | get_strategy_llm()

    debate_state = state.investment_debate_state or {}
    history = debate_state.get("history", "")
    bear_history = debate_state.get("bear_history", "")
    last_bull_argument = debate_state.get("current_response", "")
    count = debate_state.get("count", 0)

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
        "debate_history": history or "(토론 누적 없음 — 독립 분석 모드)",
        "last_bull_argument": last_bull_argument or "(직전 Bull 주장 없음 — 독립 리스크 분석)",
    }

    try:
        response = chain.invoke(inputs)
        argument = f"Bear Analyst: {response.content.strip()}"
    except Exception as exc:
        argument = (
            f"Bear Analyst: (LLM 호출 실패로 리스크 우호 주장을 생성하지 못함: {exc}). "
            "Manager가 사용 가능한 정보만으로 보수적으로 판결해야 합니다."
        )

    return {
        "investment_debate_state": {
            "history": _append(history, argument),
            "bull_history": debate_state.get("bull_history", ""),
            "bear_history": _append(bear_history, argument),
            "current_response": argument,
            "count": count + 1,
        }
    }


def _append(existing: str, new_line: str) -> str:
    if not existing:
        return new_line
    return f"{existing}\n{new_line}"
