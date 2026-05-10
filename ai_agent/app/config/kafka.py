import os
from functools import lru_cache

from dotenv import load_dotenv
from kafka import KafkaConsumer, KafkaProducer
from kafka.errors import NoBrokersAvailable

load_dotenv()


class KafkaTopic:
    MARKET_SIGNAL_DETECTED = "market.signal.detected"
    AI_TRIGGER_REQUESTED = "ai.trigger.requested"
    AI_DECISION_GENERATED = "ai.decision.generated"


def _get_bootstrap_servers() -> list[str]:
    raw = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
    return [s.strip() for s in raw.split(",")]


@lru_cache(maxsize=1)
def get_kafka_producer() -> KafkaProducer:
    """
    KafkaProducer 싱글턴을 반환한다.

    value_serializer: JSON 직렬화 (백엔드와 동일)
    acks="all": 모든 브로커 확인 후 전송 완료 처리
    """
    import json

    return KafkaProducer(
        bootstrap_servers=_get_bootstrap_servers(),
        key_serializer=lambda k: k.encode("utf-8") if k else None,
        value_serializer=lambda v: json.dumps(v, ensure_ascii=False, default=str).encode("utf-8"),
        acks="all",
        request_timeout_ms=30_000,
        compression_type="gzip",
    )


def make_kafka_consumer(
    *topics: str,
    group_id: str,
    auto_offset_reset: str = "earliest",
    max_poll_interval_ms: int = 300_000,
) -> KafkaConsumer:
    """
    KafkaConsumer를 생성해서 반환한다.

    Consumer는 토픽/group_id별로 독립적으로 생성해야 하므로 lru_cache를 사용하지 않는다.
    enable_auto_commit=False: 메시지 처리 완료 후 수동으로 commit한다.
    """
    import json

    return KafkaConsumer(
        *topics,
        bootstrap_servers=_get_bootstrap_servers(),
        group_id=group_id,
        key_deserializer=lambda k: k.decode("utf-8") if k else None,
        value_deserializer=lambda v: json.loads(v.decode("utf-8")),
        enable_auto_commit=False,
        auto_offset_reset=auto_offset_reset,
        session_timeout_ms=30_000,
        heartbeat_interval_ms=10_000,
        max_poll_interval_ms=max_poll_interval_ms,
    )


def check_kafka_connection() -> bool:
    """Kafka 연결 가능 여부 확인용 helper."""
    try:
        get_kafka_producer()
        return True
    except NoBrokersAvailable:
        return False
