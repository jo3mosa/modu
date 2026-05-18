"""event_publisher

유효한 rule_ids → MarketTriggerEvent payload 생성 → Kafka 발행
→ 성공 시 cooldown 등록 (architecture: "발행 성공 후" 강제 순서).

payload 명세 (`ai_agent/app/triggers/schemas.py` 의 `MarketTriggerEvent` 와 정렬):
    {
      "event_type": "MARKET_EVENT",
      "stock_code": str,
      "timestamp":  ISO 8601 datetime,
      "trigger": {
        "rule_ids":       [...],         # 발화한 rule ID 리스트
        "trigger_reason": [...],         # 한국어 사유
        "lv_map":         {...},         # rule_id → Lv (1/2/3, 조합 rule 만)
        "max_lv":         int,           # 발화 중 최강 Lv (0 = 단일 rule 만 발화)
        "meta_map":       {...},         # rule_id → {lv, expected_direction, warning?}
      },
      "analysis_snapshot": dict  # Signal.signals 그대로
    }

Lv 메타는 triggers_map.json (백테스트 산출물) 에서 사전 로드. 파일 없거나 매핑 안 된
rule (단일 RSI/MFI 등) 은 Lv 메타 없이 발행 — AI Agent 가 default Lv.0 으로 처리.

의도적 비포함: effect_mean / ci_95_*  — AI Agent 가 과거 수치에 anchor 되지 않도록
Lv(강도) + expected_direction(방향) 만 전달. 상세 수치 필요 시 ai_agent 측에서
triggers_map.json 직접 로드.

main.py 사이클 흐름 안 위치:
    signal   = signal_builder.build(stock_code)
    rule_ids = detection_engine.detect(signal)
    valid    = cooldown_manager.filter_active(stock_code, rule_ids)
    if valid:
        event_publisher.publish(stock_code, valid, signal)   # ← 이 모듈
"""

import json
import logging
from pathlib import Path

from clients.kafka_client import KafkaTopic, publish_event
from engine import cooldown_manager
from engine.detection_engine import RULE_REASONS
from engine.signal_builder import Signal

logger = logging.getLogger(__name__)


# ─── Lv 메타 로드 (triggers_map.json) ─────────────────────────────────────
# 백테스트 산출물의 가설 ID(B1, B28 등) 를 라이브 rule_id(REV-001 등) 로 매핑.
# 단일 rule (RSI-001, MFI-001 등) 은 매핑 X — Lv 메타 없이 발행됨.

_BACKTEST_TO_LIVE_ID = {
    "B1":  "REV-001", "B2":  "REV-002",
    "B4":  "REV-003", "B5":  "REV-004", "B6":  "REV-005",
    "B28": "EVT-001",
    "B35": "QUAL-001",
    "C2":  "TPL-001", "C3":  "TPL-002",
    "C4":  "TPL-003", "C6":  "TPL-004",
}

_TRIGGERS_MAP_PATH = (
    Path(__file__).resolve().parents[1]
    / "scripts" / "backtest" / "results" / "triggers_map.json"
)


def _load_triggers_meta() -> dict[str, dict]:
    """triggers_map.json → live rule_id → meta dict.

    포함 필드:
        lv:                  강도 (1/2/3, 백테스트 effect 절댓값 기반 분류)
        expected_direction:  positive / negative / two_sided — AI 매매 방향 결정
        warning:             low_sample 등 신중 사용 메타 (선택)

    의도적으로 effect_mean / ci_95_* 는 payload 에서 제외 — AI Agent 가 과거 백테스트
    수치에 anchor 되지 않도록 Lv(강도) 와 expected_direction(방향) 만 노출한다.
    상세 수치가 필요하면 ai_agent 측에서 triggers_map.json 자체 로드.

    매핑 안 된 rule_id (단일 RSI/MFI 등) 는 빈 결과 — 호출 측이 .get(rid) 로 None 처리.
    파일 자체가 없으면 빈 dict 반환 (호환 모드).
    """
    if not _TRIGGERS_MAP_PATH.exists():
        logger.warning("triggers_map.json 없음 — payload Lv 메타 미포함 (호환 모드)")
        return {}

    try:
        with open(_TRIGGERS_MAP_PATH, encoding="utf-8") as f:
            payload = json.load(f)
    except (OSError, json.JSONDecodeError):
        logger.exception("triggers_map.json 로드 실패 — payload Lv 메타 미포함")
        return {}

    meta: dict[str, dict] = {}
    for lv_key, items in payload.get("triggers", {}).items():
        # "Lv.3" / "Lv.2" / "Lv.1" / "Lv.0" → 정수.
        try:
            lv = int(lv_key.split(".")[1].split("_")[0])
        except (IndexError, ValueError):
            continue
        for item in items:
            live_id = _BACKTEST_TO_LIVE_ID.get(item.get("id"))
            if not live_id:
                continue
            entry: dict = {
                "lv":                 lv,
                "expected_direction": item.get("expected_direction"),
            }
            if item.get("warning"):
                entry["warning"] = item["warning"]
            meta[live_id] = entry
    logger.info("triggers_map 로드 — %d rule 의 Lv 메타 보유", len(meta))
    return meta


# 모듈 import 시 1회만 로드 — payload 빌드 핫패스에서 file IO 회피.
# triggers_map.json 갱신 시 분석 서버 재시작 필요.
_TRIGGERS_META: dict[str, dict] = _load_triggers_meta()


def _build_payload(stock_code: str, rule_ids: list[str], signal: Signal) -> dict:
    """Signal + rule_ids → MarketTriggerEvent payload.

    trigger_reason 은 RULE_REASONS 매핑. RULES 에 등록됐지만 REASONS 에 없는
    rule_id (코드 미스매치) 는 rule_id 자체로 fallback — silent loss 방지.

    Lv 메타 (lv_map / max_lv / meta_map):
      백테스트 산출물 triggers_map.json 에서 사전 로드. 매핑 안 된 rule_id
      (단일 RSI/MFI 등 17개) 는 lv_map / meta_map 에서 제외 — AI Agent 가
      Lv.0 default 로 처리한다.
      max_lv = 발화 중 최강 Lv. 단일 rule 만 발화하면 0.
    """
    lv_map: dict[str, int] = {}
    meta_map: dict[str, dict] = {}
    for rid in rule_ids:
        meta = _TRIGGERS_META.get(rid)
        if meta is None:
            continue
        lv_map[rid] = meta["lv"]
        meta_map[rid] = meta
    max_lv = max(lv_map.values(), default=0)

    return {
        "event_type": "MARKET_EVENT",
        "stock_code": stock_code,
        # signal.timestamp 는 KST tz-aware datetime → isoformat() 으로 ISO 8601.
        # pydantic 이 +09:00 / Z 둘 다 파싱하므로 KST 그대로 전송.
        "timestamp":  signal.timestamp.isoformat(),
        "trigger": {
            "rule_ids":       rule_ids,
            "trigger_reason": [RULE_REASONS.get(rid, rid) for rid in rule_ids],
            "lv_map":         lv_map,
            "max_lv":         max_lv,
            "meta_map":       meta_map,
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

    # publish_event 는 KafkaError 만 catch — JSON 직렬화 오류 / unexpected type 등
    # 다른 예외는 escape. publish() 계약(True/False)을 항상 만족시키도록 한 번 더 래핑.
    try:
        ok = publish_event(
            topic=KafkaTopic.MARKET_SIGNAL_DETECTED,
            key=stock_code,
            payload=payload,
        )
    except Exception:
        logger.exception(
            "publish_event raised (stock=%s rules=%s) — cooldown 미등록, 다음 cycle 재시도",
            stock_code, rule_ids,
        )
        return False
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
