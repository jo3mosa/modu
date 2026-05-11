from app.state.investment_state import InvestmentAgentState
from app.triggers.schemas import UserTriggerEvent


def build_state_from_user_trigger(
    event: UserTriggerEvent,
) -> InvestmentAgentState:
    """
    UserTriggerEvent를 LangGraph 실행용 InvestmentAgentState로 변환한다.

    DA 명세 정합화 후 변경 사항:
    - `candidate_assets`는 DA 메시지에 없으므로 stock_code 기반으로 자체 구성한다.
    - `market_snapshot`은 DA 메시지에 없으므로 빈 dict로 둔다 (별도 source에서 채울 예정).
    - `trigger_reason`은 `event.trigger.trigger_reason`에서 참조한다.
    """

    candidate_assets = [{"stock_code": event.stock_code}]

    return InvestmentAgentState(
        user_id=event.user_id,
        analysis_snapshot=event.analysis_snapshot,
        candidate_assets=candidate_assets,
        portfolio_snapshot=event.portfolio_snapshot,
        user_context=event.user_context,
    )
