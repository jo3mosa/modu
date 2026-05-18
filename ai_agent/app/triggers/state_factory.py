from app.state.investment_state import InvestmentAgentState
from app.triggers.schemas import UserTriggerEvent


def build_state_from_user_trigger(
    event: UserTriggerEvent,
) -> InvestmentAgentState:
    """
    UserTriggerEvent를 LangGraph 실행용 InvestmentAgentState로 변환한다.

    Analysis Layer 명세 정합화 후 변경 사항:
    - `candidate_assets`는 Analysis Layer 메시지에 없으므로 stock_code 기반으로 자체 구성한다.
    - `trigger` 정보(rule_ids / trigger_reason)는 InvestmentAgentState에 별도 필드로 저장하지 않는다.
      향후 LangSmith metadata 태깅 또는 LLM 프롬프트 입력에서 활용할 때 별도 작업으로 추가 검토.
    """

    candidate_assets = [{"stock_code": event.stock_code}]

    return InvestmentAgentState(
        user_id=event.user_id,
        as_of=event.as_of,
        analysis_snapshot=event.analysis_snapshot,
        candidate_assets=candidate_assets,
        portfolio_snapshot=event.portfolio_snapshot,
    )
