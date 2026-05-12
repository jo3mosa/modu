"""cooldown_manager

Redis `cooldown:{stock_code}:{rule_id}` 키를 통한 룰별 발화 억제.

architecture spec 의 Rule별 TTL 표를 단일 소스로 보유:
  - 지속성 룰 (과매수·과매도, 밴드 이탈 지속, 변동성 폭발 등) → cooldown 등록
  - 교차/복귀성 룰 (RSI-003/004, MACD-001/002, BB-003, SMA-001~004) → 미등록
    이유: `이전값 AND 현재값` 비교라 동일 캔들 안에서 중복 발화 구조적 불가

흐름:
  detection_engine            → 충족 rule_ids 후보
  cooldown_manager.filter_active → 활성 cooldown 제외
  event_publisher.publish      → Kafka send
  성공 시에만 cooldown_manager.register
  ↑ 순서 중요: 발행 전 register 하면 Kafka 실패 시 rule 이 묶인 채 이벤트 미전달.
"""

import logging

from clients.redis_client import get_redis_client

logger = logging.getLogger(__name__)


# Rule별 cooldown TTL (초). 표에 없는 rule_id 는 cooldown 미적용 (교차/복귀성).
# architecture 문서 변경 시 여기 같이 갱신.
COOLDOWN_TTL_SECONDS: dict[str, int] = {
    # 과매수·과매도 지속 — 4h
    "RSI-001": 4 * 3600,
    "RSI-002": 4 * 3600,
    "MFI-001": 4 * 3600,
    "MFI-002": 4 * 3600,

    # 밴드 이탈 지속 — 2h
    "BB-001":  2 * 3600,
    "BB-002":  2 * 3600,

    # 감성 — 2h
    "SENT-001": 2 * 3600,
    "SENT-002": 2 * 3600,

    # 변동성 폭발 — 1h
    "ATR-001": 1 * 3600,

    # 가격 급변 — 1h
    "PRICE-001": 1 * 3600,
    "PRICE-002": 1 * 3600,

    # 거래량 급증 — 1h
    "VOL-001":   1 * 3600,

    # 공시 — 24h
    "DART-001": 24 * 3600,
    "DART-002": 24 * 3600,
    "DART-003": 24 * 3600,
    "DART-004": 24 * 3600,
    "DART-005": 24 * 3600,

    # 미등록 (교차/복귀성):
    #   RSI-003 / RSI-004        — 70/30 라인 교차
    #   MACD-001 / MACD-002       — golden / dead cross
    #   BB-003                    — 밴드 내 복귀
    #   SMA-001 ~ SMA-004         — 이동평균 교차
}


def _key(stock_code: str, rule_id: str) -> str:
    return f"cooldown:{stock_code}:{rule_id}"


def filter_active(stock_code: str, rule_ids: list[str]) -> list[str]:
    """활성 cooldown 인 rule_id 제외하고 남은 것 반환.

    미등록 룰(교차/복귀성)은 키가 절대 SET 되지 않으므로 자연 통과.
    Redis pipeline 으로 한 번의 round trip 으로 EXISTS 일괄 조회.
    """
    if not rule_ids:
        return []

    client = get_redis_client()
    pipe = client.pipeline()
    for rid in rule_ids:
        pipe.exists(_key(stock_code, rid))
    results = pipe.execute()

    return [rid for rid, exists in zip(rule_ids, results) if not exists]


def register(stock_code: str, rule_id: str) -> None:
    """cooldown 키 등록. Rule별 TTL 적용. 표에 없는 rule_id 는 noop.

    !! 반드시 Kafka 발행 성공 후 호출 !!
    발행 전 등록 시 Kafka 실패하면 rule 이 묶인 채 이벤트 미전달.
    """
    ttl = COOLDOWN_TTL_SECONDS.get(rule_id)
    if ttl is None:
        # 교차/복귀성 룰 — 의도된 noop. 디버깅 편의를 위해 debug 레벨로만 흘림.
        logger.debug("cooldown skip (no TTL): stock=%s rule=%s", stock_code, rule_id)
        return
    get_redis_client().set(_key(stock_code, rule_id), "1", ex=ttl)
