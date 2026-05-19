from pathlib import Path
from typing import Any

from app.config.llm import get_debate_llm
from app.observability.langsmith_helpers import add_run_metadata
from app.state.investment_state import InvestmentAgentState
from app.utils.agent_message import publish_agent_message
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

    chain = load_prompt(str(_PROMPT_PATH)) | get_debate_llm()

    debate_state = state.investment_debate_state or {}
    bull_history: list[str] = debate_state.get("bull_history", [])
    bear_history: list[str] = debate_state.get("bear_history", [])
    debate_history: str = debate_state.get("history", "")
    latest_bear_argument: str | None = debate_state.get("latest_bear_argument")
    round_count: int = debate_state.get("round_count", 0)
    current_round = round_count + 1  # 계산만, 증가는 Bear에서

    add_run_metadata({"node": "bull_researcher", "round": current_round})

    signals = state.analysis_snapshot.get("signals", {}) if state.analysis_snapshot else {}
    mc = state.memory_context or {}

    inputs = {
        "candidate_assets": to_json(state.candidate_assets),
        "signals_technical": to_json(signals.get("technical", {})),
        "signals_fundamental": to_json(signals.get("fundamental", {})),
        "signals_event": to_json(signals.get("event", {})),
        "signals_sentiment": to_json(signals.get("sentiment", {})),
        "portfolio_snapshot": to_json(state.portfolio_snapshot),
        "user_context": to_json(state.user_context),
        "policy_context": to_json(state.policy_context),
        "memory_lessons_aggregate": to_json(mc.get("lessons_aggregate", [])),
        "memory_loss_pattern_brief": mc.get("loss_pattern_brief", "(해당 없음)"),
        "memory_similar_decisions": to_json(mc.get("similar_decisions_table", [])),
        "memory_recent_post_mortems": to_json(mc.get("recent_post_mortems", [])),
        "history_context": to_json(state.history_context),
        # 시간순 통합 대화 history — 자기 이전 라운드 발언 + Bear 발언 모두 포함.
        # 자기 일관성 유지 + Bear 주장 인용·반박 가능.
        "debate_history": debate_history or "(아직 토론 기록 없음 — 첫 발언입니다)",
        "last_bear_argument": latest_bear_argument or "(첫 라운드 — 직전 Bear 주장 없음)",
    }

    try:
        response = chain.invoke(inputs)
        argument = f"Bull Analyst: {response.content.strip()}"
        publish_agent_message(state, "BULL", round_count * 2, response.content.strip())
    except Exception as exc:
        argument = (
            f"Bull Analyst: (LLM 호출 실패로 매수 우호 주장을 생성하지 못함: {exc}). "
            "Bear의 독립 분석과 Manager 판결로 진행합니다."
        )

    # 발언을 시간순 history에 append — Bull → Bear → Bull → Bear 순서로 누적.
    new_history = f"{debate_history}\n{argument}" if debate_history else argument

    return {
        "investment_debate_state": {
            "history": new_history,
            "bull_history": bull_history + [argument],
            "bear_history": bear_history,
            "debate_rounds": debate_state.get("debate_rounds", []),
            "latest_bull_argument": argument,
            "latest_bear_argument": latest_bear_argument,
            "round_count": round_count,  # Bear에서만 증가
        }
    }


