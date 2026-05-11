"""cooldown_manager

Redis `cooldown:{stock_code}:{rule_id}` 조회·등록.
- filter: Cooldown 중인 rule_id 제외 (event_publisher 발행 전)
- register: Kafka 발행 성공 직후 호출 (Rule별 TTL 적용)

발행 전 등록은 Kafka 실패 시 Cooldown만 잡히는 문제를 일으키므로 금지.
"""


def filter_active(stock_code: str, rule_ids: list[str]) -> list[str]:
    raise NotImplementedError("cooldown_manager.filter_active is not implemented yet")


def register(stock_code: str, rule_id: str) -> None:
    raise NotImplementedError("cooldown_manager.register is not implemented yet")
