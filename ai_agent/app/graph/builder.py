from langgraph.graph import END, StateGraph

from app.agents.critic_agent import critic_agent
from app.agents.memory_agent import memory_agent
from app.agents.risk_guard import risk_guard
from app.agents.strategy_agent import strategy_agent
from app.agents.supervisor_agent import supervisor_agent
from app.runtime.executor import executor
from app.state.investment_state import InvestmentAgentState


def route_after_supervisor(state: InvestmentAgentState) -> str:
    if state.flow_status == "hold":
        return "end"
    return "risk_guard"


def route_after_risk_guard(state: InvestmentAgentState) -> str:
    if state.risk_cleared:
        return "executor"
    return "end"


def build_investment_graph():
    graph = StateGraph(InvestmentAgentState)

    graph.add_node("memory_agent", memory_agent)
    graph.add_node("strategy_agent", strategy_agent)
    graph.add_node("critic_agent", critic_agent)
    graph.add_node("supervisor_agent", supervisor_agent)
    graph.add_node("risk_guard", risk_guard)
    graph.add_node("executor", executor)

    graph.set_entry_point("memory_agent")

    graph.add_edge("memory_agent", "strategy_agent")
    graph.add_edge("strategy_agent", "critic_agent")
    graph.add_edge("critic_agent", "supervisor_agent")

    graph.add_conditional_edges(
        "supervisor_agent",
        route_after_supervisor,
        {
            "risk_guard": "risk_guard",
            "end": END,
        },
    )

    graph.add_conditional_edges(
        "risk_guard",
        route_after_risk_guard,
        {
            "executor": "executor",
            "end": END,
        },
    )

    graph.add_edge("executor", END)

    return graph.compile()