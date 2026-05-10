from pathlib import Path
from typing import Any

from app.config.llm import get_strategy_llm
from app.state.investment_state import InvestmentAgentState
from app.utils.json_utils import to_json
from app.utils.prompt_loader import load_prompt

_PROMPT_PATH = Path(__file__).resolve().parents[2] / "config" / "prompts" / "bull_researcher.txt"


def bull_researcher(state: InvestmentAgentState) -> dict[str, Any]:
    """
    Bull Researcher.

    역할:
    - 매수 옹호 입장에서 자유 텍스트로 토론 발언을 생성한다.
    - investment_debate_state.history에 "Bull Analyst:" 화자 prefix와 함께 누적한다.
    - 출력은 자유 텍스트이며, Manager가 전체 history를 종합해 판결한다.

    실패 시 정책:
    - LLM 호출이 실패하면 fallback 발언을 history에 남기고 다음 노드로 진행한다.
      (Bull 발언이 비어도 Bear가 독립 분석할 수 있고 Manager가 hold로 종합 가능)
    """

    chain = load_prompt(str(_PROMPT_PATH)) | get_strategy_llm()

    debate_state = state.investment_debate_state or {}
    history = debate_state.get("history", "")
    bull_history = debate_state.get("bull_history", "")
    last_bear_argument = debate_state.get("current_response", "")
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
        "debate_history": history or "(첫 라운드 — 토론 누적 없음)",
        "last_bear_argument": last_bear_argument or "(첫 라운드 — 직전 Bear 주장 없음)",
    }

    try:
        response = chain.invoke(inputs)
        argument = f"Bull Analyst: {response.content.strip()}"
    except Exception as exc:
        argument = (
            f"Bull Analyst: (LLM 호출 실패로 매수 우호 주장을 생성하지 못함: {exc}). "
            "Bear의 독립 분석과 Manager 판결로 진행합니다."
        )

    return {
        "investment_debate_state": {
            "history": _append(history, argument),
            "bull_history": _append(bull_history, argument),
            "bear_history": debate_state.get("bear_history", ""),
            "current_response": argument,
            "count": count + 1,
        }
    }


def _append(existing: str, new_line: str) -> str:
    if not existing:
        return new_line
    return f"{existing}\n{new_line}"
