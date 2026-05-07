from app.cache.threshold_cache_repository import ThresholdCacheRepository
from app.position_monitoring.schemas import PositionEvent, PriceTick


class PositionMonitor:
    """
    실시간 현재가를 기준으로 사용자별 포지션 이벤트를 생성한다.

    핵심 원칙:
    - 모든 사용자를 조회하지 않는다.
    - 현재가가 들어온 종목을 감시 중인 사용자만 Redis index로 찾는다.
    - 목표가/손절가 도달은 AI 판단이 아니라 Position Event로 만든다.
    """

    def __init__(
        self,
        threshold_repository: ThresholdCacheRepository,
    ) -> None:
        self.threshold_repository = threshold_repository

    def detect_events(
        self,
        tick: PriceTick,
    ) -> list[PositionEvent]:
        """
        현재가 tick 하나를 받아 발생한 Position Event 목록을 반환한다.
        """

        events: list[PositionEvent] = []

        user_ids = self.threshold_repository.get_user_ids_by_stock(
            tick.stock_code
        )

        for user_id in user_ids:
            threshold = self.threshold_repository.get_threshold(
                user_id=user_id,
                stock_code=tick.stock_code,
            )

            if threshold is None:
                continue

            target_price = threshold.get("target_price")
            stop_loss_price = threshold.get("stop_loss_price")

            if isinstance(target_price, (int, float)) and tick.current_price >= target_price:
                events.append(
                    PositionEvent(
                        user_id=user_id,
                        stock_code=tick.stock_code,
                        event_type="TARGET_PRICE_HIT",
                        current_price=tick.current_price,
                        threshold=threshold,
                        timestamp=tick.timestamp,
                    )
                )

            if isinstance(stop_loss_price, (int, float)) and tick.current_price <= stop_loss_price:
                events.append(
                    PositionEvent(
                        user_id=user_id,
                        stock_code=tick.stock_code,
                        event_type="STOP_LOSS_PRICE_HIT",
                        current_price=tick.current_price,
                        threshold=threshold,
                        timestamp=tick.timestamp,
                    )
                )

        return events