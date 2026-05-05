from langgraph.graph import END, StateGraph

from app.agents.critic_agent import critic_agent
from app.agents.memory.memory_agent import memory_agent
from app.agents.risk_guard import risk_guard
from app.agents.strategy_agent import strategy_agent
from app.agents.supervisor_agent import supervisor_agent
from app.runtime.executor import executor
from app.state.investment_state import InvestmentAgentState


def route_after_supervisor(state: InvestmentAgentState) -> str:
    """
    Supervisor Agent 이후 다음 노드를 결정한다.

    - flow_status가 hold이면 그래프 종료
    - 그 외에는 Risk Guard로 이동
    """

    if state.flow_status == "hold":
        return "end"
    return "risk_guard"


def route_after_risk_guard(state: InvestmentAgentState) -> str:
    """
    Risk Guard 이후 다음 노드를 결정한다.

    - risk_cleared=True이면 Executor로 이동
    - False이면 그래프 종료

    이 조건부 라우팅으로 인해 Risk Guard를 통과하지 못한 결정은
    Executor로 전달되지 않는다.
    """

    if state.risk_cleared:
        return "executor"
    return "end"


def build_investment_graph():
    """
    투자 의사결정 LangGraph를 생성하고 compile한다.

    전체 흐름:
    memory_agent
      → strategy_agent
      → critic_agent
      → supervisor_agent
      → 조건부 분기
      → risk_guard
      → 조건부 분기
      → executor
      → END
    """

    graph = StateGraph(InvestmentAgentState)

    # 그래프 노드 등록
    graph.add_node("memory_agent", memory_agent)
    graph.add_node("strategy_agent", strategy_agent)
    graph.add_node("critic_agent", critic_agent)
    graph.add_node("supervisor_agent", supervisor_agent)
    graph.add_node("risk_guard", risk_guard)
    graph.add_node("executor", executor)

    # 시작 노드 설정
    graph.set_entry_point("memory_agent")

    # 기본 직선 흐름
    graph.add_edge("memory_agent", "strategy_agent")
    graph.add_edge("strategy_agent", "critic_agent")
    graph.add_edge("critic_agent", "supervisor_agent")

    # Supervisor 결과에 따라 Risk Guard로 갈지 종료할지 결정
    graph.add_conditional_edges(
        "supervisor_agent",
        route_after_supervisor,
        {
            "risk_guard": "risk_guard",
            "end": END,
        },
    )

    # Risk Guard 통과 여부에 따라 Executor로 갈지 종료할지 결정
    graph.add_conditional_edges(
        "risk_guard",
        route_after_risk_guard,
        {
            "executor": "executor",
            "end": END,
        },
    )

    # Executor 실행 후 종료
    graph.add_edge("executor", END)

    return graph.compile()