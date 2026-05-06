from app.triggers.mock_trigger import create_mock_user_trigger
from app.triggers.state_factory import build_state_from_user_trigger

from app.graph.builder import build_investment_graph


def run_mock_trigger_pipeline():
    """
    mock UserTriggerEvent를 이용해 Reasoning Layer 실행 흐름을 검증한다.

    흐름:
    1. mock UserTriggerEvent 생성
    2. UserTriggerEvent → InvestmentAgentState 변환
    3. LangGraph 실행
    4. 최종 state 반환
    """

    event = create_mock_user_trigger()
    state = build_state_from_user_trigger(event)

    graph = build_investment_graph()
    result = graph.invoke(state)

    return result


if __name__ == "__main__":
    result = run_mock_trigger_pipeline()
    print(result)