from app.repositories.position_cache_repository import PositionCacheRepository
from app.repositories.position_index_repository import PositionIndexRepository
from app.repositories.trade_rule_cache_repository import TradeRuleCacheRepository
from app.repositories.position_event_cooldown_repository import (
    PositionEventCooldownRepository,
)

from app.position_monitoring.schemas import PositionEvent, PriceTick


class PositionMonitor:
    """
    실시간 현재가를 기준으로 사용자별 포지션 이벤트를 생성한다.

    핵심 원칙:
    - 모든 사용자를 조회하지 않는다.
    - 현재가가 들어온 종목을 실제 보유 중인 사용자만 Redis index로 찾는다.
    - 사용자의 평균단가 기준 손익률을 계산한다.
    - 익절/손절 조건 도달 시 Position Event를 생성한다.
    - 동일 이벤트 반복 발생은 cooldown으로 제한한다.
    """

    COOLDOWN_SECONDS = 300 # 이벤트 재발생 방지 위해 5분 cooldown 적용

    def __init__(
        self,
        position_index_repository: PositionIndexRepository,
        position_cache_repository: PositionCacheRepository,
        trade_rule_repository: TradeRuleCacheRepository,
        cooldown_repository: PositionEventCooldownRepository,
    ) -> None:
        self.position_index_repository = position_index_repository
        self.position_cache_repository = position_cache_repository
        self.trade_rule_repository = trade_rule_repository
        self.cooldown_repository = cooldown_repository

    def detect_events(
        self,
        tick: PriceTick,
    ) -> list[PositionEvent]:
        """
        현재가 tick 하나를 받아 발생한 Position Event 목록을 반환한다.
        """

        events: list[PositionEvent] = []

        user_ids = self.position_index_repository.get_user_ids_by_stock(
            tick.stock_code
        )

        for user_id in user_ids:
            position = self.position_cache_repository.get_position(
                user_id=user_id,
                stock_code=tick.stock_code,
            )

            if position is None:
                continue

            trade_rule = self.trade_rule_repository.get_trade_rule(
                user_id=user_id,
            )

            if trade_rule is None:
                continue

            average_price = position.get("average_price")

            if not isinstance(average_price, (int, float)):
                continue

            if average_price <= 0:
                continue

            profit_rate = (
                (tick.current_price - average_price)
                / average_price
            ) * 100

            profit_rate = round(profit_rate, 2)

            take_profit_rate = trade_rule.get(
                "take_profit_rate"
            )

            stop_loss_rate = trade_rule.get(
                "stop_loss_rate"
            )

            # ==============================
            # 익절 이벤트 생성
            # ==============================

            if (
                isinstance(take_profit_rate, (int, float))
                and profit_rate >= take_profit_rate
            ):
                if self.cooldown_repository.is_cooldown_active(
                    user_id=user_id,
                    stock_code=tick.stock_code,
                    event_type="TAKE_PROFIT_RATE_HIT",
                ):
                    continue

                events.append(
                    PositionEvent(
                        user_id=user_id,
                        stock_code=tick.stock_code,
                        event_type="TAKE_PROFIT_RATE_HIT",
                        current_price=tick.current_price,
                        trade_rule=trade_rule,
                        position=position,
                        profit_rate=profit_rate,
                        timestamp=tick.timestamp,
                    )
                )

                self.cooldown_repository.activate_cooldown(
                    user_id=user_id,
                    stock_code=tick.stock_code,
                    event_type="TAKE_PROFIT_RATE_HIT",
                    ttl_seconds=self.COOLDOWN_SECONDS,
                )

            # ==============================
            # 손절 이벤트 생성
            # ==============================

            if (
                isinstance(stop_loss_rate, (int, float))
                and profit_rate <= stop_loss_rate
            ):
                if self.cooldown_repository.is_cooldown_active(
                    user_id=user_id,
                    stock_code=tick.stock_code,
                    event_type="STOP_LOSS_RATE_HIT",
                ):
                    continue

                events.append(
                    PositionEvent(
                        user_id=user_id,
                        stock_code=tick.stock_code,
                        event_type="STOP_LOSS_RATE_HIT",
                        current_price=tick.current_price,
                        trade_rule=trade_rule,
                        position=position,
                        profit_rate=profit_rate,
                        timestamp=tick.timestamp,
                    )
                )

                self.cooldown_repository.activate_cooldown(
                    user_id=user_id,
                    stock_code=tick.stock_code,
                    event_type="STOP_LOSS_RATE_HIT",
                    ttl_seconds=self.COOLDOWN_SECONDS,
                )

        return events