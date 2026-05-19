from typing import Literal

from langgraph.graph import END, StateGraph

from app.agents.decision.decision_manager import decision_manager
from app.agents.decision.risk_gate import risk_gate
from app.agents.strategy.bear_researcher import bear_researcher
from app.agents.strategy.bull_researcher import bull_researcher
from app.agents.strategy.strategy_manager import strategy_manager
from app.context.context_loader import context_loader
from app.state.investment_state import InvestmentAgentState

GraphMode = Literal["debate_0", "debate_1", "debate_2"]

# mode → Bull/Bear 토론 라운드 수. 신규 round 추가 시 여기에만 항목 추가.
_DEBATE_ROUNDS: dict[str, int] = {
    "debate_0": 0,
    "debate_1": 1,
    "debate_2": 2,
}


def route_after_decision_manager(state: InvestmentAgentState) -> str:
    """
    Decision Manager 이후 다음 노드를 결정한다.

    - flow_status가 hold이면 그래프 종료
    - 그 외에는 Risk Gate로 이동
    """

    if state.flow_status == "hold":
        return "end"
    return "risk_gate"


def build_investment_graph(mode: GraphMode = "debate_1"):
    """
    투자 의사결정 LangGraph를 생성하고 compile한다.

    mode (Bull/Bear 토론 라운드 수에 따른 변형):
        "debate_0": context_loader → strategy_manager 직결 (토론 0회, ablation).
            strategy_manager는 빈 debate history를 자연스럽게 fallback 처리한다.
        "debate_1" (기본, 실시간 = MVP): Bull → Bear 1라운드 → strategy_manager.
        "debate_2": Bull ↔ Bear 2라운드 (round 2의 Bull은 직전 Bear 주장을 보고 반박)
            → strategy_manager.

    전체 흐름 (debate_N, N ≥ 1):
    context_loader → bull → bear → (round_count<N: bull로 루프 | else: strategy_manager)
      → decision_manager → (hold면 END) → risk_gate → END

    전체 흐름 (debate_0):
    context_loader → strategy_manager → decision_manager
      → (hold면 END) → risk_gate → END
    """

    target_rounds = _DEBATE_ROUNDS[mode]

    graph = StateGraph(InvestmentAgentState)

    graph.add_node("context_loader", context_loader)
    graph.add_node("strategy_manager", strategy_manager)
    graph.add_node("decision_manager", decision_manager)
    graph.add_node("risk_gate", risk_gate)

    graph.set_entry_point("context_loader")

    if target_rounds > 0:
        graph.add_node("bull_researcher", bull_researcher)
        graph.add_node("bear_researcher", bear_researcher)
        graph.add_edge("context_loader", "bull_researcher")
        graph.add_edge("bull_researcher", "bear_researcher")

        def route_after_bear(state: InvestmentAgentState) -> str:
            # round_count는 bear_researcher가 발언 완료 시점에 증가시킨다.
            round_count = (state.investment_debate_state or {}).get("round_count", 0)
            if round_count < target_rounds:
                return "bull_researcher"
            return "strategy_manager"

        graph.add_conditional_edges(
            "bear_researcher",
            route_after_bear,
            {
                "bull_researcher": "bull_researcher",
                "strategy_manager": "strategy_manager",
            },
        )
    else:
        graph.add_edge("context_loader", "strategy_manager")

    graph.add_edge("strategy_manager", "decision_manager")

    graph.add_conditional_edges(
        "decision_manager",
        route_after_decision_manager,
        {
            "risk_gate": "risk_gate",
            "end": END,
        },
    )

    graph.add_edge("risk_gate", END)

    return graph.compile()
