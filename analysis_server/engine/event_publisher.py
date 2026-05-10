"""event_publisher

유효한 rule_ids로 Market Event 생성 → Kafka 발행 → 발행 성공 후 Cooldown 키 등록.
"""


def publish(stock_code: str, rule_ids: list[str], signal) -> None:
    raise NotImplementedError("event_publisher.publish is not implemented yet")
