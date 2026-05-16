import logging
import os
from collections.abc import Callable
from functools import lru_cache

from kafka import TopicPartition
from kafka.structs import OffsetAndMetadata
from pydantic import ValidationError
from sqlalchemy.engine import Engine

from app.config.kafka import KafkaTopic, make_kafka_consumer
from app.context.user_context import create_engine_from_env
from app.feedback.pipeline import run_post_mortem
from app.feedback.schemas import TradeSettledEvent

logger = logging.getLogger(__name__)


@lru_cache(maxsize=1)
def _get_engine() -> Engine:
    """프로세스 전체에서 Engine 1회 생성 — 결정 파이프라인의 _get_shared_engine과 동일 패턴."""
    return create_engine_from_env()


def _guarded(fn: Callable[[], None]) -> Callable[[], None]:
    """기존 consumer.py와 동일 패턴: 컨슈머 스레드가 죽으면 프로세스를 종료해 k8s 재기동을 유도."""
    def wrapper() -> None:
        try:
            fn()
        except Exception:
            logger.exception("%s 비정상 종료, 프로세스를 종료합니다", fn.__name__)
            os._exit(1)
    return wrapper


def consume_trade_settled() -> None:
    """
    trade.settled 토픽 소비 루프.

    backend가 매도 체결 → PnL 확정 시점에 발행하는 TradeSettledEvent를 받아
    회고 파이프라인을 실행한다.

    실패 정책:
    - 페이로드 검증 실패 (poison pill) → 로깅 + commit + skip. 무한 재시도 방지.
    - 그 외 transient error → commit 하지 않고 다음 poll에서 재시도.
    - run_post_mortem 내부 실패는 None 반환으로 흡수되므로 여기까지 올라오지 않음.
    """
    consumer = make_kafka_consumer(
        KafkaTopic.TRADE_SETTLED,
        group_id="ai-agent-trade-settled",
        max_poll_interval_ms=600_000,  # LLM 회고 호출 여유 (10분)
    )
    engine = _get_engine()

    logger.info("trade.settled consumer started")

    try:
        for message in consumer:
            tp = TopicPartition(message.topic, message.partition)
            commit_offset = {tp: OffsetAndMetadata(message.offset + 1, None)}

            try:
                event = TradeSettledEvent.model_validate(message.value)
            except ValidationError as exc:
                logger.error(
                    "trade.settled: 잘못된 페이로드 (poison pill), commit & skip — offset=%s, error=%s",
                    message.offset,
                    exc,
                )
                consumer.commit(commit_offset)
                continue

            try:
                run_post_mortem(event, engine)
                consumer.commit(commit_offset)
            except Exception:
                logger.exception(
                    "trade.settled 처리 중 예상치 못한 오류 (retry): user_id=%s, offset=%s",
                    event.user_id,
                    message.offset,
                )
    finally:
        consumer.close()


def main() -> None:
    """
    별도 entry point.

    실행:
        python -m app.feedback.consumer

    배포 시 기존 app.consumer(결정 컨슈머)와 별도 Pod로 분리 가능.
    회고는 비동기/지연 허용 영역이라 결정 컨슈머와 자원/장애 격리하는 게 안전하다.
    """
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    )
    _guarded(consume_trade_settled)()


if __name__ == "__main__":
    main()
