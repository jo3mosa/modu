from langgraph.graph import END, StateGraph

from app.agents.decision.decision_manager import decision_manager
from app.agents.decision.risk_gate import risk_gate
from app.agents.memory.memory_agent import memory_agent
from app.agents.strategy.bear_researcher import bear_researcher
from app.agents.strategy.bull_researcher import bull_researcher
from app.agents.strategy.strategy_manager import strategy_manager
from app.runtime.executor import executor
from app.state.investment_state import InvestmentAgentState


def route_after_decision_manager(state: InvestmentAgentState) -> str:
    """
    Decision Manager 이후 다음 노드를 결정한다.

    - flow_status가 hold이면 그래프 종료
    - 그 외에는 Risk Gate로 이동
    """

    if state.flow_status == "hold":
        return "end"
    return "risk_gate"


def route_after_risk_gate(state: InvestmentAgentState) -> str:
    """
    Risk Gate 이후 다음 노드를 결정한다.

    - risk_cleared=True이면 Executor로 이동
    - False이면 그래프 종료
    """

    if state.risk_cleared:
        return "executor"
    return "end"


def build_investment_graph():
    """
    투자 의사결정 LangGraph를 생성하고 compile한다.

    전체 흐름:
    memory_agent
      → bull_researcher
      → bear_researcher
      → strategy_manager
      → decision_manager
      → 조건부 분기
      → risk_gate
      → 조건부 분기
      → executor
      → END

    Strategy Team(bull/bear/manager)이 BUY/SELL 방향과 1차 가격을 결정하고,
    Decision Manager가 사이즈/타이밍/시나리오/risk_level까지 채워 실행 가능한 거래안을 만든다.
    Risk Gate는 deterministic hard rule로 최종 게이트를 수행한다.
    """

    graph = StateGraph(InvestmentAgentState)

    graph.add_node("memory_agent", memory_agent)
    graph.add_node("bull_researcher", bull_researcher)
    graph.add_node("bear_researcher", bear_researcher)
    graph.add_node("strategy_manager", strategy_manager)
    graph.add_node("decision_manager", decision_manager)
    graph.add_node("risk_gate", risk_gate)
    graph.add_node("executor", executor)

    graph.set_entry_point("memory_agent")

    graph.add_edge("memory_agent", "bull_researcher")
    graph.add_edge("bull_researcher", "bear_researcher")
    graph.add_edge("bear_researcher", "strategy_manager")
    graph.add_edge("strategy_manager", "decision_manager")

    graph.add_conditional_edges(
        "decision_manager",
        route_after_decision_manager,
        {
            "risk_gate": "risk_gate",
            "end": END,
        },
    )

    graph.add_conditional_edges(
        "risk_gate",
        route_after_risk_gate,
        {
            "executor": "executor",
            "end": END,
        },
    )

    graph.add_edge("executor", END)

    return graph.compile()
