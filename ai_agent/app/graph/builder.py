from typing import Literal

from langgraph.graph import END, StateGraph

from app.agents.decision.decision_manager import decision_manager
from app.agents.decision.risk_gate import risk_gate
from app.agents.strategy.bear_researcher import bear_researcher
from app.agents.strategy.bull_researcher import bull_researcher
from app.agents.strategy.strategy_manager import strategy_manager
from app.context.context_loader import context_loader
from app.state.investment_state import InvestmentAgentState

GraphMode = Literal["A", "B"]


def route_after_decision_manager(state: InvestmentAgentState) -> str:
    """
    Decision Manager 이후 다음 노드를 결정한다.

    - flow_status가 hold이면 그래프 종료
    - 그 외에는 Risk Gate로 이동
    """

    if state.flow_status == "hold":
        return "end"
    return "risk_gate"


def build_investment_graph(mode: GraphMode = "A"):
    """
    투자 의사결정 LangGraph를 생성하고 compile한다.

    mode:
        "A" (기본, 실시간 = MVP): Bull/Bear 토론 → Strategy Manager 판결
        "B" (실험 2 비교군): context_loader → Strategy Manager 직결.
            토론 ablation을 위한 단일 에이전트 모드. strategy_manager는
            빈 debate history를 자연스럽게 fallback 처리한다.

    전체 흐름 (mode A):
    context_loader → bull → bear → strategy_manager → decision_manager
      → (hold면 END) → risk_gate → END

    전체 흐름 (mode B):
    context_loader → strategy_manager → decision_manager
      → (hold면 END) → risk_gate → END
    """

    graph = StateGraph(InvestmentAgentState)

    graph.add_node("context_loader", context_loader)
    graph.add_node("strategy_manager", strategy_manager)
    graph.add_node("decision_manager", decision_manager)
    graph.add_node("risk_gate", risk_gate)

    graph.set_entry_point("context_loader")

    if mode == "A":
        graph.add_node("bull_researcher", bull_researcher)
        graph.add_node("bear_researcher", bear_researcher)
        graph.add_edge("context_loader", "bull_researcher")
        graph.add_edge("bull_researcher", "bear_researcher")
        graph.add_edge("bear_researcher", "strategy_manager")
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
