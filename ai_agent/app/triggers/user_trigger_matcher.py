from app.triggers.schemas import MarketTriggerEvent, TriggerType, UserTriggerEvent
from app.repositories.position_index_repository import (
    PositionIndexRepository,
    RedisPositionIndexRepository,
)

_POSITION_INDEX_REPOSITORY: PositionIndexRepository = RedisPositionIndexRepository()

# TODO: 백엔드 API 또는 DB에서 조회하도록 교체
_MOCK_USER_CONTEXT_BY_USER_ID: dict[int, dict] = {
    1: {
        "investment_style": "balanced",
        "risk_level": "medium",
        "strategy_note": "대형주 중심으로 안정적인 수익을 선호",
    },
    2: {
        "investment_style": "aggressive",
        "risk_level": "high",
        "strategy_note": "모멘텀 강한 종목에 적극 대응",
    },
    3: {
        "investment_style": "conservative",
        "risk_level": "low",
        "strategy_note": "손실 회피 성향이 강함",
    },
}


_MOCK_PORTFOLIO_SNAPSHOT_BY_USER_ID: dict[int, dict] = {
    1: {
        "cash_balance": 1_000_000,
        "positions": [
            {
                "stock_code": "005930",
                "stock_name": "삼성전자",
                "quantity": 10,
                "average_price": 75000,
            }
        ],
    },
    2: {
        "cash_balance": 500_000,
        "positions": [
            {
                "stock_code": "005930",
                "stock_name": "삼성전자",
                "quantity": 5,
                "average_price": 72000,
            }
        ],
    },
    3: {
        "cash_balance": 2_000_000,
        "positions": [
            {
                "stock_code": "000660",
                "stock_name": "SK하이닉스",
                "quantity": 3,
                "average_price": 180000,
            }
        ],
    },
}

def get_position_index_repository() -> PositionIndexRepository:
    return _POSITION_INDEX_REPOSITORY



def get_holding_user_ids(
    stock_code: str,
    repository: PositionIndexRepository | None = None,
) -> list[int]:
    """
    특정 종목을 보유한 사용자 목록을 조회한다.

    repository가 직접 주입되면 해당 구현체를 사용하고,
    없으면 현재 기본 repository를 조회한다.
    """
    repository = repository or get_position_index_repository()
    return repository.get_user_ids_by_stock(stock_code)


def get_user_context(user_id: int) -> dict:
    """
    사용자별 투자 성향, 리스크 설정, 자연어 전략 등을 조회한다.

    현재는 mock 데이터 기반이다.
    """
    return _MOCK_USER_CONTEXT_BY_USER_ID.get(user_id, {})


def get_portfolio_snapshot(user_id: int) -> dict:
    """
    사용자별 포트폴리오 스냅샷을 조회한다.

    Reasoning Layer는 이 정보를 바탕으로
    실제 보유 수량, 현금, 평균 단가 등을 고려해 판단한다.
    """
    return _MOCK_PORTFOLIO_SNAPSHOT_BY_USER_ID.get(user_id, {})


def match_market_event_to_users(
    event: MarketTriggerEvent,
) -> list[UserTriggerEvent]:
    """
    MarketTriggerEvent를 사용자별 UserTriggerEvent로 변환한다.

    처리 흐름:
    1. MarketTriggerEvent에서 stock_code를 읽는다.
    2. 해당 종목을 보유한 사용자 목록을 조회한다.
    3. 사용자별 portfolio_snapshot / user_context를 조회한다.
    4. 시장 이벤트 정보와 사용자 정보를 합쳐 UserTriggerEvent를 생성한다.
    5. 매칭 대상 사용자가 없으면 빈 list를 반환한다.

    주의:
    - 이 함수는 Reasoning Layer를 직접 실행하지 않는다.
    - Reasoning Layer가 실행할 수 있는 사용자 단위 이벤트만 생성한다.
    """

    holding_user_ids = get_holding_user_ids(event.stock_code)

    if not holding_user_ids:
        return []

    user_trigger_events: list[UserTriggerEvent] = []

    for user_id in holding_user_ids:
        portfolio_snapshot = get_portfolio_snapshot(user_id)
        user_context = get_user_context(user_id)

        # TODO: 동일 user_id / stock_code / source_event_id 중복 방지
        # 추후 Redis cooldown 또는 event deduplication 저장소를 붙여서
        # 같은 시장 이벤트가 동일 사용자에게 반복 실행되지 않도록 처리한다.

        user_trigger_event = UserTriggerEvent(
            source_event_id=event.event_id,
            event_type=TriggerType.MARKET_EVENT,
            timestamp=event.timestamp,
            user_id=user_id,
            stock_code=event.stock_code,
            trigger=event.trigger,
            analysis_snapshot=event.analysis_snapshot,
            portfolio_snapshot=portfolio_snapshot,
            user_context=user_context,
        )

        user_trigger_events.append(user_trigger_event)

    return user_trigger_events