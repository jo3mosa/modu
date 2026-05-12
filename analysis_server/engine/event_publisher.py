"""event_publisher

유효한 rule_ids → MarketTriggerEvent payload 생성 → Kafka 발행
→ 성공 시 cooldown 등록 (architecture: "발행 성공 후" 강제 순서).

payload 명세는 `ai_agent/app/triggers/schemas.py` 의 `MarketTriggerEvent` 와 1:1:
    {
      "event_type": "MARKET_EVENT",
      "stock_code": str,
      "timestamp":  ISO 8601 datetime,
      "trigger":    {"rule_ids": [...], "trigger_reason": [...]},
      "analysis_snapshot": dict  # Signal.signals 그대로
    }

main.py 사이클 흐름 안 위치:
    signal   = signal_builder.build(stock_code)
    rule_ids = detection_engine.detect(signal)
    valid    = cooldown_manager.filter_active(stock_code, rule_ids)
    if valid:
        event_publisher.publish(stock_code, valid, signal)   # ← 이 모듈
"""

import logging

from clients.kafka_client import KafkaTopic, publish_event
from engine import cooldown_manager
from engine.detection_engine import RULE_REASONS
from engine.signal_builder import Signal

logger = logging.getLogger(__name__)


def _build_payload(stock_code: str, rule_ids: list[str], signal: Signal) -> dict:
    """Signal + rule_ids → MarketTriggerEvent payload.

    trigger_reason 은 RULE_REASONS 매핑. RULES 에 등록됐지만 REASONS 에 없는
    rule_id (코드 미스매치) 는 rule_id 자체로 fallback — silent loss 방지.
    """
    return {
        "event_type": "MARKET_EVENT",
        "stock_code": stock_code,
        # signal.timestamp 는 KST tz-aware datetime → isoformat() 으로 ISO 8601.
        # pydantic 이 +09:00 / Z 둘 다 파싱하므로 KST 그대로 전송.
        "timestamp":  signal.timestamp.isoformat(),
        "trigger": {
            "rule_ids":       rule_ids,
            "trigger_reason": [RULE_REASONS.get(rid, rid) for rid in rule_ids],
        },
        # Signal.signals 는 이미 analysis_signals 구조 — 그대로 매핑.
        "analysis_snapshot": signal.signals,
    }


def publish(stock_code: str, rule_ids: list[str], signal: Signal) -> bool:
    """Market Event 발행 + 성공 시 cooldown 등록. 발행 성공 True, 실패 False.

    !! 발행 → cooldown 등록 순서 강제 !!
    Kafka 실패 시 cooldown 미등록 → 다음 cycle 에서 자연 재시도.

    Args:
        stock_code : 종목 6자리 (Kafka partition key = stock_code → 종목별 순서 보존)
        rule_ids   : cooldown_manager.filter_active 통과한 유효 룰. 빈 리스트는 호출 X.
        signal     : 발화 시점의 Signal snapshot — analysis_snapshot 으로 그대로 전달.
    """
    if not rule_ids:
        # caller 가 빈 리스트로 부르면 안 되지만 안전망.
        return False

    payload = _build_payload(stock_code, rule_ids, signal)

    ok = publish_event(
        topic=KafkaTopic.MARKET_SIGNAL_DETECTED,
        key=stock_code,
        payload=payload,
    )
    if not ok:
        # publish_event 가 이미 KafkaError 로그 — 여기선 비즈니스 컨텍스트만.
        logger.warning(
            "Market Event publish failed (stock=%s rules=%s) — cooldown 미등록, 다음 cycle 재시도",
            stock_code, rule_ids,
        )
        return False

    # 발행 확정 후 cooldown 등록. 등록 자체가 실패해도 (Redis 일시 단절 등)
    # 다음 cycle 에서 같은 rule 이 다시 발화될 수는 있으나 손해는 추가 1회 발행뿐.
    for rid in rule_ids:
        try:
            cooldown_manager.register(stock_code, rid)
        except Exception:
            logger.exception("cooldown register failed (stock=%s rule=%s)", stock_code, rid)

    logger.info("published Market Event: stock=%s rules=%s", stock_code, rule_ids)
    return True
