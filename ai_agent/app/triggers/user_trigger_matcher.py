from app.repositories.buy_candidate_repository import (
    BuyCandidateRepository,
    RedisBuyCandidateRepository,
)
from app.repositories.market_price_repository import (
    MarketPriceRepository,
    RedisMarketPriceRepository,
)
from app.repositories.portfolio_snapshot_repository import (
    PortfolioSnapshotRepository,
    RedisPortfolioSnapshotRepository,
)
from app.repositories.position_index_repository import (
    PositionIndexRepository,
    RedisPositionIndexRepository,
)
from app.triggers.schemas import MarketTriggerEvent, TriggerType, UserTriggerEvent

_POSITION_INDEX_REPOSITORY: PositionIndexRepository = RedisPositionIndexRepository()
_PORTFOLIO_SNAPSHOT_REPOSITORY: PortfolioSnapshotRepository = RedisPortfolioSnapshotRepository()
_MARKET_PRICE_REPOSITORY: MarketPriceRepository = RedisMarketPriceRepository()
_BUY_CANDIDATE_REPOSITORY: BuyCandidateRepository = RedisBuyCandidateRepository()


def get_position_index_repository() -> PositionIndexRepository:
    return _POSITION_INDEX_REPOSITORY


def get_portfolio_snapshot_repository() -> PortfolioSnapshotRepository:
    return _PORTFOLIO_SNAPSHOT_REPOSITORY


def get_market_price_repository() -> MarketPriceRepository:
    return _MARKET_PRICE_REPOSITORY


def get_buy_candidate_repository() -> BuyCandidateRepository:
    return _BUY_CANDIDATE_REPOSITORY


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
    buy_candidate_repository: BuyCandidateRepository | None = None,
) -> list[UserTriggerEvent]:
    """
    MarketTriggerEvent를 사용자별 UserTriggerEvent로 변환한다.

    처리 흐름:
    1. 해당 종목을 보유한 모든 사용자를 조회한다 (is_holder=True).
    2. buy_candidate_repository가 주입된 경우, risk_grade >= stock_tier 인
       전체 적격 유저에서 보유자를 제외한 비보유자를 추가한다 (is_holder=False).
       - 보유자와 겹치는 유저는 보유자로 처리한다.
    3. 사용자별 portfolio_snapshot에 current_price를 포함해 UserTriggerEvent를 생성한다.
    4. 매칭 대상 사용자가 없으면 빈 list를 반환한다.

    주의:
    - 이 함수는 Reasoning Layer를 직접 실행하지 않는다.
    - is_holder=False 이벤트는 run_and_publish에서 SELL/HOLD 결과 시 발행이 생략된다.
    """

    holding_user_ids = get_holding_user_ids(event.stock_code, position_index_repository)
    holder_set = set(holding_user_ids)

    # (user_id, is_holder) 쌍으로 처리 대상 구성
    targets: list[tuple[int, bool]] = [(uid, True) for uid in holding_user_ids]

    stock_tier: int | None = None
    non_holder_grades: dict[int, int] = {}  # {user_id: risk_grade}

    if buy_candidate_repository is not None:
        stock_tier, eligible_grades = buy_candidate_repository.get_eligible_user_grades(event.stock_code)
        for uid, grade in eligible_grades.items():
            if uid not in holder_set:
                targets.append((uid, False))
                non_holder_grades[uid] = grade

    if not targets:
        return []

    current_price = get_current_price(event.stock_code, market_price_repository)

    user_trigger_events: list[UserTriggerEvent] = []

    for user_id, is_holder in targets:
        portfolio_snapshot = get_portfolio_snapshot(user_id, portfolio_snapshot_repository)
        portfolio_snapshot = {**portfolio_snapshot, "current_price": current_price}

        # TODO: 동일 user_id / stock_code / source_event_id 중복 방지
        # 추후 Redis cooldown 또는 event deduplication 저장소를 붙여서
        # 같은 시장 이벤트가 동일 사용자에게 반복 실행되지 않도록 처리한다.

        user_trigger_events.append(UserTriggerEvent(
            source_event_id=event.event_id,
            event_type=TriggerType.MARKET_EVENT,
            timestamp=event.timestamp,
            user_id=user_id,
            stock_code=event.stock_code,
            trigger=event.trigger,
            analysis_snapshot=event.analysis_snapshot,
            portfolio_snapshot=portfolio_snapshot,
            is_holder=is_holder,
            stock_tier=stock_tier,
            matched_risk_grade=non_holder_grades.get(user_id) if not is_holder else None,
        ))

    return user_trigger_events
