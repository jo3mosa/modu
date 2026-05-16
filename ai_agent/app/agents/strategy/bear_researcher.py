from pathlib import Path
from typing import Any

from app.config.llm import get_debate_llm
from app.observability.langsmith_helpers import add_run_metadata
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

    chain = load_prompt(str(_PROMPT_PATH)) | get_debate_llm()

    debate_state = state.investment_debate_state or {}
    bear_history: list[str] = debate_state.get("bear_history", [])
    debate_rounds: list[dict] = debate_state.get("debate_rounds", [])
    latest_bull_argument: str | None = debate_state.get("latest_bull_argument")
    round_count: int = debate_state.get("round_count", 0)
    current_round = round_count + 1  # 이 Bear 발언으로 완성될 라운드 번호

    add_run_metadata({"node": "bear_researcher", "round": current_round})

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
        "last_bull_argument": latest_bull_argument or "(직전 Bull 주장 없음 — 독립 리스크 분석)",
    }

    try:
        response = chain.invoke(inputs)
        argument = f"Bear Analyst: {response.content.strip()}"
    except Exception as exc:
        argument = (
            f"Bear Analyst: (LLM 호출 실패로 리스크 우호 주장을 생성하지 못함: {exc}). "
            "Manager가 사용 가능한 정보만으로 보수적으로 판결해야 합니다."
        )

    new_round = {
        "round": current_round,
        "bull": latest_bull_argument or "",
        "bear": argument,
    }

    return {
        "investment_debate_state": {
            "bull_history": debate_state.get("bull_history", []),
            "bear_history": bear_history + [argument],
            "debate_rounds": debate_rounds + [new_round],
            "latest_bull_argument": latest_bull_argument,
            "latest_bear_argument": argument,
            "round_count": current_round,  # Bear 발언 완료 시점에 증가
        }
    }


