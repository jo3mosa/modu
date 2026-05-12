"""kafka_client

analysis_server 전용 Kafka producer wrapper.

ai_agent/app/config/kafka.py 패턴 미러:
  - sync `KafkaProducer` 싱글톤 (lru_cache)
  - JSON value_serializer (한글 보존, default=str 로 datetime 등 안전)
  - key 는 UTF-8 string (partition key = stock_code 권장)
  - acks="all" 내구성 + gzip compression

analysis_server 는 publish 전용. consumer 는 ai_agent / backend 책임.
"""

import json
import os
from functools import lru_cache

from dotenv import load_dotenv
from kafka import KafkaProducer
from kafka.errors import KafkaError, NoBrokersAvailable

load_dotenv()


class KafkaTopic:
    """ai_agent / backend 와 공유되는 topic 명. 변경 시 모든 서비스 동시 갱신 필요.

    ai_agent/app/config/kafka.py 와 1:1 일치하도록 유지.
    """
    MARKET_SIGNAL_DETECTED = "market.signal.detected"


# 단일 send 가 broker ack 받기까지 기다리는 기본 timeout.
# event_publisher 는 "발행 확정 후 cooldown 등록" 패턴이라 sync 결과가 필요.
PUBLISH_DEFAULT_TIMEOUT_SEC = 10.0


def _bootstrap_servers() -> list[str]:
    raw = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
    return [s.strip() for s in raw.split(",") if s.strip()]


@lru_cache(maxsize=1)
def get_kafka_producer() -> KafkaProducer:
    """프로세스 단위 싱글톤 KafkaProducer.

    함수 + lru_cache 구조 이유:
      1. import 시점에 broker 연결을 강제하지 않음 (Kafka down 이어도 import 가능)
      2. 테스트에서 monkeypatch 로 교체 가능
      3. KafkaProducer 자체가 thread-safe 라 싱글톤으로 충분
    """
    return KafkaProducer(
        bootstrap_servers=_bootstrap_servers(),
        key_serializer=lambda k: k.encode("utf-8") if k else None,
        value_serializer=lambda v: json.dumps(v, ensure_ascii=False, default=str).encode("utf-8"),
        acks="all",
        request_timeout_ms=30_000,
        compression_type="gzip",
    )


def check_kafka_connection() -> bool:
    """startup / health check 용. broker 도달 불가 시 False."""
    try:
        get_kafka_producer()
        return True
    except NoBrokersAvailable:
        return False


def publish_event(
    topic: str,
    key: str | None,
    payload: dict,
    timeout: float = PUBLISH_DEFAULT_TIMEOUT_SEC,
) -> bool:
    """sync send + ack 대기. 성공 True / 실패 False.

    architecture: "Cooldown 키 등록은 Kafka 발행 성공 이후" — caller 는 이 함수의
    반환값을 보고 cooldown 등록 여부 결정. fire-and-forget 으로 만들면
    발행 실패 시 cooldown 만 잡혀 다음 cycle 까지 이벤트 미전달 위험.

    Args:
        topic   : Kafka topic 명 (KafkaTopic 상수 권장)
        key     : partition key. None 이면 random partition.
                  stock_code 를 key 로 두면 같은 종목 이벤트가 순서 보존됨.
        payload : JSON-serializable dict. datetime 은 default=str 로 ISO 변환됨.
        timeout : broker ack 대기 시간 (초). 초과 시 False.
    """
    producer = get_kafka_producer()
    try:
        future = producer.send(topic, key=key, value=payload)
        future.get(timeout=timeout)   # block until ack — KafkaError on failure
        return True
    except KafkaError:
        # caller (event_publisher) 가 False 보고 cooldown 등록 skip → 다음 cycle 재발행 가능
        return False
