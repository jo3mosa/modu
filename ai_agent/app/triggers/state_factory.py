from app.state.investment_state import InvestmentAgentState
from app.triggers.schemas import UserTriggerEvent


def build_state_from_user_trigger(
    event: UserTriggerEvent,
) -> InvestmentAgentState:
    """
    UserTriggerEvent를 LangGraph 실행용 InvestmentAgentState로 변환한다.

    UserTriggerEvent는 Market Event 또는 Position Event가
    사용자 포트폴리오/투자 성향/리스크 설정과 결합된 최종 실행 이벤트이다.

    LangGraph는 사용자별 판단을 수행해야 하므로,
    시장 단위 이벤트가 아니라 UserTriggerEvent를 기준으로 실행된다.
    """

    return InvestmentAgentState(
        market_snapshot=event.market_snapshot,
        analysis_snapshot=event.analysis_snapshot,
        candidate_assets=event.candidate_assets,
        portfolio_snapshot=event.portfolio_snapshot,

        user_context=event.user_context,

        trigger_context={
            "event_id": event.event_id,
            "source_event_id": event.source_event_id,
            "trigger_type": event.trigger_type,
            "trigger_reason": event.trigger_reason,
            "user_id": event.user_id,
            "stock_code": event.stock_code,
            "source": event.source,
        },
    )