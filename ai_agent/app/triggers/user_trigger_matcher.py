from app.triggers.schemas import MarketTriggerEvent, TriggerType, UserTriggerEvent
from app.repositories.position_index_repository import (
    PositionIndexRepository,
    RedisPositionIndexRepository,
)
from app.repositories.portfolio_snapshot_repository import (
    PortfolioSnapshotRepository,
    RedisPortfolioSnapshotRepository,
)
from app.repositories.market_price_repository import (
    MarketPriceRepository,
    RedisMarketPriceRepository,
)

_POSITION_INDEX_REPOSITORY: PositionIndexRepository = RedisPositionIndexRepository()
_PORTFOLIO_SNAPSHOT_REPOSITORY: PortfolioSnapshotRepository = RedisPortfolioSnapshotRepository()
_MARKET_PRICE_REPOSITORY: MarketPriceRepository = RedisMarketPriceRepository()

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


def get_position_index_repository() -> PositionIndexRepository:
    return _POSITION_INDEX_REPOSITORY


def get_portfolio_snapshot_repository() -> PortfolioSnapshotRepository:
    return _PORTFOLIO_SNAPSHOT_REPOSITORY


def get_market_price_repository() -> MarketPriceRepository:
    return _MARKET_PRICE_REPOSITORY


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


def get_portfolio_snapshot(
    user_id: int,
    repository: PortfolioSnapshotRepository | None = None,
) -> dict:
    """
    사용자별 포트폴리오 스냅샷을 Redis에서 조회한다.

    Reasoning Layer는 이 정보를 바탕으로
    실제 보유 수량, 현금, 평균 단가 등을 고려해 판단한다.
    """
    repository = repository or get_portfolio_snapshot_repository()
    return repository.get(user_id)


def get_current_price(
    stock_code: str,
    repository: MarketPriceRepository | None = None,
) -> int | None:
    """
    종목별 실시간 현재가를 Redis에서 조회한다.

    WebSocket 수신 즉시 갱신되는 값으로,
    Reasoning Layer의 목표가/손절가 산출 및 주문 수량 계산 기준가로 사용된다.
    """
    repository = repository or get_market_price_repository()
    return repository.get(stock_code)


def match_market_event_to_users(
    event: MarketTriggerEvent,
    position_index_repository: PositionIndexRepository | None = None,
    portfolio_snapshot_repository: PortfolioSnapshotRepository | None = None,
    market_price_repository: MarketPriceRepository | None = None,
) -> list[UserTriggerEvent]:
    """
    MarketTriggerEvent를 사용자별 UserTriggerEvent로 변환한다.

    처리 흐름:
    1. MarketTriggerEvent에서 stock_code를 읽는다.
    2. 해당 종목을 보유한 사용자 목록을 조회한다.
    3. 사용자별 portfolio_snapshot / user_context를 조회한다.
    4. 종목 현재가를 조회해 portfolio_snapshot에 current_price로 포함한다.
    5. 시장 이벤트 정보와 사용자 정보를 합쳐 UserTriggerEvent를 생성한다.
    6. 매칭 대상 사용자가 없으면 빈 list를 반환한다.

    주의:
    - 이 함수는 Reasoning Layer를 직접 실행하지 않는다.
    - Reasoning Layer가 실행할 수 있는 사용자 단위 이벤트만 생성한다.
    """

    holding_user_ids = get_holding_user_ids(event.stock_code, position_index_repository)

    if not holding_user_ids:
        return []

    current_price = get_current_price(event.stock_code, market_price_repository)

    user_trigger_events: list[UserTriggerEvent] = []

    for user_id in holding_user_ids:
        portfolio_snapshot = get_portfolio_snapshot(user_id, portfolio_snapshot_repository)

        portfolio_snapshot = {**portfolio_snapshot, "current_price": current_price}

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
