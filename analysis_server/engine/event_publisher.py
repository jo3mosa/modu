"""event_publisher

유효한 rule_ids → MarketTriggerEvent payload 생성 → Kafka 발행
→ 성공 시 cooldown 등록 (architecture: "발행 성공 후" 강제 순서).

payload 명세는 `ai_agent/app/triggers/schemas.py` 의 `MarketTriggerEvent` 와 1:1:
    {
      "event_type": "MARKET_EVENT",
      "stock_code": str,
      "timestamp":  ISO 8601 datetime,
      "trigger":    {"rule_ids": [...], "trigger_reason": [...]},
      "analysis_snapshot": dict  # Signal.signals + news_summary 주입
    }

뉴스 요약 (news_summary) 은 트리거 발화 시점 - rule 별 윈도우 범위의 기사를
MongoDB modu_mongo.news_articles 에서 가져와 Haiku 4.5 로 요약 → analysis_snapshot
하위에 주입. LLM/Mongo 실패해도 발행은 계속됨 (요약은 부가 정보, 필수 아님).

방침: Lv·effect 같은 강도 메타는 AI Agent 에 전달하지 않는다. LLM 의 자율성 보존
(가중치 anchor 회피) — 모든 trigger 는 동일 weight 로 전달, 의사결정은 AI 가 자율적으로.
백테스트 결과의 Lv 분류는 분석 리포트용으로만 존재.

main.py 사이클 흐름 안 위치:
    signal   = signal_builder.build(stock_code)
    rule_ids = detection_engine.detect(signal)
    valid    = cooldown_manager.filter_active(stock_code, rule_ids)
    if valid:
        event_publisher.publish(stock_code, valid, signal)   # ← 이 모듈
"""

import atexit
import logging
import os
from typing import Optional

from dotenv import load_dotenv

try:
    from pymongo import MongoClient
    from pymongo.errors import PyMongoError
except ImportError:
    MongoClient = None
    PyMongoError = Exception

from clients import llm_client
from clients.kafka_client import KafkaTopic, publish_event
from engine import cooldown_manager
from engine.detection_engine import RULE_REASONS, pick_news_window, window_to_timedelta
from engine.signal_builder import Signal

load_dotenv()

logger = logging.getLogger(__name__)


# 요약 prompt 에 포함할 기사 본문 길이 cap (자 단위) — 너무 길면 토큰 폭증,
# 너무 짧으면 맥락 손실. 한국 금융 기사 평균 본문이 1500~3000자 수준.
ARTICLE_BODY_CHAR_CAP = 1500

# payload top_articles 에 담을 기사 최대 개수 — 가장 최근 N건만.
TOP_ARTICLES_LIMIT = 5

# LLM 요약에 투입할 기사 최대 개수 — 너무 많으면 토큰 폭증. 최근순 cutoff.
SUMMARY_ARTICLES_LIMIT = 30


# ─── MongoDB 연결 (모듈 단위 lazy 싱글톤) ────────────────────────────────────

_mongo_collection = None
_mongo_initialized = False


def _get_news_collection():
    """modu_mongo.news_articles collection 반환. 실패 시 None — caller 는 skip.

    news_collector._connect_mongo 와 동일 패턴. 초기화 1회만 시도 (성공/실패
    모두 캐싱) — 매 사이클마다 재시도하지 않음.
    """
    global _mongo_collection, _mongo_initialized
    if _mongo_initialized:
        return _mongo_collection
    _mongo_initialized = True

    if MongoClient is None:
        logger.warning("pymongo 미설치 — 뉴스 요약 skip")
        return None

    uri = os.getenv("MONGO_URI")
    if not uri:
        logger.warning("MONGO_URI 환경변수 없음 — 뉴스 요약 skip")
        return None

    try:
        client = MongoClient(uri, serverSelectionTimeoutMS=5000)
        client.admin.command("ping")
        atexit.register(client.close)
        _mongo_collection = client["modu_mongo"]["news_articles"]
        logger.info("✓ MongoDB 연결 — modu_mongo.news_articles (event_publisher)")
        return _mongo_collection
    except Exception:
        logger.exception("MongoDB 연결 실패 — 뉴스 요약 skip")
        return None


# ─── 뉴스 요약 ──────────────────────────────────────────────────────────────

_SUMMARY_SYSTEM_PROMPT = (
    "당신은 한국 주식 시장 뉴스 분석 보조 시스템이다. "
    "주어진 종목 관련 뉴스들을 읽고 AI 매매 에이전트가 의사결정에 활용할 수 있도록 "
    "객관적·간결하게 요약한다. 다음 원칙을 지킨다:\n"
    "1. 매수/매도 추천이나 가격 예측은 하지 않는다.\n"
    "2. 시간 흐름을 따라 정리한다 (오래된 → 최신).\n"
    "3. 호재·악재 둘 다 균형 있게 포함한다.\n"
    "4. 출처가 여러 매체일 경우 공통된 사실과 매체별 차이를 구분한다.\n"
    "5. 출력은 한국어 평문, 4~6문장."
)


def _fetch_news_for_summary(stock_code: str, anchor_dt, window) -> list[dict]:
    """anchor_dt 기준 window 범위 이전 기사들을 최근순으로 조회.

    anchor_dt 는 KST tz-aware datetime (signal.timestamp). MongoDB 의
    published_at 은 문자열(ISO) 로 저장돼 있어 lexicographic 비교가 의도대로 동작
    (ISO 8601 의 특성).

    Returns: 기사 dict 리스트 (최대 SUMMARY_ARTICLES_LIMIT). 실패/0건은 [].
    """
    coll = _get_news_collection()
    if coll is None:
        return []

    delta = window_to_timedelta(window)
    from_dt = anchor_dt - delta

    # published_at 은 ISO 문자열로 저장됨 — `Z`/오프셋 포함 형식 둘 다 호환.
    # tz 정보 잘릴 위험 회피 위해 KST naive ISO 까지 양쪽 비교.
    from_iso = from_dt.isoformat()
    to_iso = anchor_dt.isoformat()

    try:
        cursor = (
            coll.find(
                {
                    "stock_codes": stock_code,
                    "published_at": {"$gte": from_iso, "$lte": to_iso},
                },
                {
                    "_id": 0,
                    "title": 1,
                    "content": 1,
                    "summary": 1,
                    "url": 1,
                    "published_at": 1,
                    "source_name": 1,
                    "sentiment_score": 1,
                },
            )
            .sort("published_at", -1)
            .limit(SUMMARY_ARTICLES_LIMIT)
        )
        return list(cursor)
    except PyMongoError:
        logger.exception("MongoDB 조회 실패 (stock=%s)", stock_code)
        return []


def _build_summary_prompt(stock_code: str, window: tuple[str, int], articles: list[dict]) -> str:
    """기사 리스트 → LLM user prompt 텍스트."""
    kind, value = window
    window_label = f"{value}{'일' if kind == 'days' else '시간'}"

    lines = [
        f"[종목코드] {stock_code}",
        f"[조회 윈도우] 트리거 발화 시점 이전 {window_label}",
        f"[기사 수] {len(articles)}건",
        "",
        "다음은 시간 역순(최신 → 과거) 기사 목록이다. 본문은 길이 제한으로 일부 잘림.",
        "",
    ]
    for i, art in enumerate(articles, 1):
        title = art.get("title") or "(제목 없음)"
        published = art.get("published_at") or "-"
        source = art.get("source_name") or "-"
        body = (art.get("content") or art.get("summary") or "").strip()
        if len(body) > ARTICLE_BODY_CHAR_CAP:
            body = body[:ARTICLE_BODY_CHAR_CAP] + "…"
        sent = art.get("sentiment_score")
        sent_str = f" sentiment={sent}" if sent is not None else ""
        lines.append(f"--- ({i}) {published} | {source}{sent_str}")
        lines.append(f"제목: {title}")
        lines.append(f"본문: {body}")
        lines.append("")

    lines.append("위 기사들을 4~6문장으로 시간 흐름 순으로 요약하라.")
    return "\n".join(lines)


def _summarize_news(stock_code: str, rule_ids: list[str], signal: Signal) -> Optional[dict]:
    """트리거 발화 종목의 뉴스 요약 dict 생성.

    Returns:
        {
          "summary": str,
          "window": {"kind": "days"|"hours", "value": int},
          "article_count": int,
          "top_articles": [{title, url, published_at, sentiment_score}, ...]
        }
        또는 None (Mongo/LLM 사용 불가 / 기사 0건).
    """
    window = pick_news_window(rule_ids)
    articles = _fetch_news_for_summary(stock_code, signal.timestamp, window)
    if not articles:
        logger.info("뉴스 요약 skip (기사 0건): stock=%s window=%s", stock_code, window)
        return None

    prompt = _build_summary_prompt(stock_code, window, articles)
    summary_text = llm_client.summarize(system=_SUMMARY_SYSTEM_PROMPT, user=prompt)
    if not summary_text:
        # LLM 실패 — 기사 메타만이라도 보내면 agent 가 최소 sentiment 보조는 가능.
        logger.info("LLM 요약 실패 — 메타만 포함: stock=%s", stock_code)

    top = articles[:TOP_ARTICLES_LIMIT]
    return {
        "summary":        summary_text,   # None 일 수도 있음 — agent 가 처리.
        "window":         {"kind": window[0], "value": window[1]},
        "article_count":  len(articles),
        "top_articles":   [
            {
                "title":           a.get("title"),
                "url":             a.get("url"),
                "published_at":    a.get("published_at"),
                "sentiment_score": a.get("sentiment_score"),
            }
            for a in top
        ],
    }


# ─── payload 빌드 & 발행 ────────────────────────────────────────────────────

def _build_payload(stock_code: str, rule_ids: list[str], signal: Signal) -> dict:
    """Signal + rule_ids → MarketTriggerEvent payload.

    trigger_reason 은 RULE_REASONS 매핑. RULES 에 등록됐지만 REASONS 에 없는
    rule_id (코드 미스매치) 는 rule_id 자체로 fallback — silent loss 방지.

    analysis_snapshot 에 news_summary 주입 (있을 때만). signal.signals 원본은
    훼손하지 않도록 shallow copy 후 키 추가.
    """
    snapshot = dict(signal.signals)   # shallow copy — top-level 키 추가만 함.
    news_summary = _summarize_news(stock_code, rule_ids, signal)
    if news_summary is not None:
        snapshot["news_summary"] = news_summary

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
        "analysis_snapshot": snapshot,
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
