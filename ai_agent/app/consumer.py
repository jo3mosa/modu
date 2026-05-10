import logging
import os
import threading
from collections.abc import Callable

from kafka import TopicPartition
from kafka.structs import OffsetAndMetadata

from app.config.kafka import KafkaTopic, get_kafka_producer, make_kafka_consumer
from app.graph.runner import run_and_publish
from app.triggers.schemas import MarketTriggerEvent, UserTriggerEvent
from app.triggers.user_trigger_matcher import match_market_event_to_users

logger = logging.getLogger(__name__)


def _guarded(fn: Callable[[], None]) -> Callable[[], None]:
    def wrapper() -> None:
        try:
            fn()
        except Exception:
            logger.exception("%s 비정상 종료, 프로세스를 종료합니다", fn.__name__)
            os._exit(1)
    return wrapper


def _consume_market_signals() -> None:
    """
    market.signal.detected 토픽 소비 루프.

    Analysis Layer가 발행한 MarketTriggerEvent를 수신하고,
    보유 사용자별 UserTriggerEvent로 변환해 ai.trigger.requested 토픽에 발행한다.
    """
    consumer = make_kafka_consumer(
        KafkaTopic.MARKET_SIGNAL_DETECTED,
        group_id="ai-agent-market-signal",
    )
    producer = get_kafka_producer()

    logger.info("market.signal.detected consumer started")

    try:
        for message in consumer:
            try:
                event = MarketTriggerEvent.model_validate(message.value)
                user_events = match_market_event_to_users(event)

                futures = [
                    producer.send(
                        KafkaTopic.AI_TRIGGER_REQUESTED,
                        key=str(user_event.user_id),
                        value=user_event.model_dump(),
                    )
                    for user_event in user_events
                ]
                for future in futures:
                    future.get(timeout=30)

                if user_events:
                    logger.info(
                        "ai.trigger.requested published: %d users, stock_code=%s",
                        len(user_events),
                        event.stock_code,
                    )

                consumer.commit({
                    TopicPartition(message.topic, message.partition): OffsetAndMetadata(message.offset + 1, None)
                })
            except Exception:
                logger.exception("market.signal.detected 처리 실패: offset=%s", message.offset)
    finally:
        consumer.close()


def _consume_user_triggers() -> None:
    """
    ai.trigger.requested 토픽 소비 루프.

    UserTriggerEvent를 수신하고 LangGraph 파이프라인을 실행한 뒤
    결과를 ai.decision.generated 토픽에 발행한다.
    """
    consumer = make_kafka_consumer(
        KafkaTopic.AI_TRIGGER_REQUESTED,
        group_id="ai-agent-user-trigger",
        max_poll_interval_ms=600_000,  # LLM 파이프라인 실행 시간 여유 확보 (10분)
    )

    logger.info("ai.trigger.requested consumer started")

    try:
        for message in consumer:
            try:
                event = UserTriggerEvent.model_validate(message.value)
                run_and_publish(event)
                consumer.commit({
                    TopicPartition(message.topic, message.partition): OffsetAndMetadata(message.offset + 1, None)
                })
            except Exception:
                user_id = message.value.get("user_id") if isinstance(message.value, dict) else None
                logger.exception(
                    "ai.trigger.requested 처리 실패: user_id=%s, offset=%s",
                    user_id,
                    message.offset,
                )
    finally:
        consumer.close()


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    )

    t1 = threading.Thread(target=_guarded(_consume_market_signals), name="market-signal-consumer", daemon=True)
    t2 = threading.Thread(target=_guarded(_consume_user_triggers), name="user-trigger-consumer", daemon=True)

    t1.start()
    t2.start()

    logger.info("AI Agent consumers started")

    t1.join()
    t2.join()


if __name__ == "__main__":
    main()
