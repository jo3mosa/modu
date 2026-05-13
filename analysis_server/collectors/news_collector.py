"""news_collector

한국경제·연합인포맥스 RSS 비정기 폴링 → MongoDB modu_mongo.news_articles 적재.

추후 (별도 모듈에서 예정):
 - 본문 → FinBERT 감성 분석 → Redis `sentiment:{stock_code}` 갱신
 - NER 기반 종목 매핑 → MongoDB `stock_codes` 필드 backfill

backfill 용 historical 크롤러(`scripts/backfill/past_news_crawler.py`)와 같은 컬렉션
(`modu_mongo.news_articles`) 을 공유하지만 `_id` 형식이 달라 충돌 없음:
  - Historical (Naver 경유): `{oid}_{aid}`     예) `015_0004931791`
  - Real-time (RSS 직접):    `{source_key}_{numeric_id}`  예) `hankyung_2026051089231`

사용법:
    python -m collectors.news_collector            # 1회 실행
    python -m collectors.news_collector --loop     # 10분 간격 무한 루프
    python -m collectors.news_collector --loop --interval 300   # 5분 간격
"""

import argparse
import atexit
import logging
import os
import re
import sqlite3
import time
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

import feedparser
import requests
from bs4 import BeautifulSoup
from dotenv import load_dotenv

try:
    from pymongo import MongoClient
    from pymongo.errors import PyMongoError
except ImportError:
    MongoClient = None
    PyMongoError = Exception

from clients.redis_client import set_json


# ─── 설정 ────────────────────────────────────────────────────────────────────

SOURCES = [
    {
        "key":        "hankyung",
        "name":       "한국경제",
        "rss_url":    "https://www.hankyung.com/feed/finance",
        "selector":   "#articletxt",
        "id_pattern": r"/article/(\d+)",       # https://www.hankyung.com/article/2026051089231
    },
    {
        "key":        "infomax",
        "name":       "연합인포맥스",
        "rss_url":    "https://news.einfomax.co.kr/rss/allArticle.xml",
        "selector":   "#article-view-content-div",
        "id_pattern": r"idxno=(\d+)",          # https://.../articleView.html?idxno=4290000
    },
]

HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/120.0.0.0 Safari/537.36"
    ),
    "Accept-Language": "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
    "Referer": "https://www.google.com/",
}

ARTICLE_FETCH_DELAY = 1.5     # 본문 fetch 사이 sleep (초)
HTTP_TIMEOUT = 10
DEFAULT_LOOP_INTERVAL = 600   # 무한 루프 간격 — 10분 (RSS 갱신 빈도와 균형)

# ─── sentiment / 종목 매핑 설정 ─────────────────────────────────────────────

KST = ZoneInfo("Asia/Seoul")

# stock_master 캐시 위치 (collectors/ → ../data/stock_master.db).
_MODULE_DIR = os.path.dirname(os.path.abspath(__file__))
STOCK_DB_PATH = os.path.join(_MODULE_DIR, "..", "data", "stock_master.db")

# 회사명 substring 매칭 최소 길이 — LG/SK 같은 2자리는 false positive 다수.
MIN_STOCK_NAME_LEN = 3

# Redis `sentiment:{stock}` TTL (architecture spec — 2시간).
SENTIMENT_REDIS_TTL = 2 * 3600

# 종목별 sentiment 집계 lookback (24시간 안의 기사만 평균).
SENTIMENT_LOOKBACK_HOURS = 24

# 확신도 → confidence_level 매핑 임곗값.
CONFIDENCE_HIGH = 70.0
CONFIDENCE_MEDIUM = 30.0

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
logger = logging.getLogger(__name__)


# ─── FinBERT analyzer 싱글톤 ────────────────────────────────────────────────
# KR-FinBERT-SC 모델 로드는 무거우니 (~500MB + GPU 초기화) 첫 사용 시 1회만.

_analyzer = None


def get_sentiment_analyzer():
    """models.sentiment_analyzer.FinancialSentimentAnalyzer 싱글톤 lazy 로드."""
    global _analyzer
    if _analyzer is None:
        # local import — 컨테이너 부팅 시점 transformers/torch 로드 회피.
        from models.sentiment_analyzer import FinancialSentimentAnalyzer
        _analyzer = FinancialSentimentAnalyzer()
    return _analyzer


# ─── 종목명 매핑 (회사명 substring) ─────────────────────────────────────────
# NER 모델 없이 stock_master 활성 회사명으로 본문 substring 검색.
# MVP — 정교한 매핑(동음이의어, 별칭)은 별도 PR.

_STOCK_NAME_TO_CODE: dict[str, str] | None = None


def _load_stock_names() -> dict[str, str]:
    """{회사명: 종목코드} dict. 활성 + 길이 ≥ MIN_STOCK_NAME_LEN 만.

    싱글톤 캐시 — 컨테이너 재기동 전엔 stock_master 변동 거의 없음.
    """
    global _STOCK_NAME_TO_CODE
    if _STOCK_NAME_TO_CODE is not None:
        return _STOCK_NAME_TO_CODE
    with sqlite3.connect(STOCK_DB_PATH) as conn:
        rows = conn.execute(
            "SELECT stock_code, stock_name FROM stock_master WHERE is_active=1"
        ).fetchall()
    _STOCK_NAME_TO_CODE = {
        name: code for code, name in rows
        if name and len(name) >= MIN_STOCK_NAME_LEN
    }
    logger.info("loaded %d stock name mappings (len>=%d)",
                len(_STOCK_NAME_TO_CODE), MIN_STOCK_NAME_LEN)
    return _STOCK_NAME_TO_CODE


def match_stocks(text: str) -> list[str]:
    """본문에서 회사명 substring 매칭 → 종목코드 리스트 (중복 제거).

    긴 이름 우선 매칭 + 매치된 부분 제거 → "삼성전자" 매치 후 그 자리에 "삼성"
    재매치 되지 않게 함.
    """
    if not text:
        return []
    names = _load_stock_names()
    # 긴 이름 먼저 매치 — substring overlap 회피
    sorted_names = sorted(names.keys(), key=len, reverse=True)
    found: list[str] = []
    seen: set[str] = set()
    remaining = text
    for name in sorted_names:
        if name in remaining:
            code = names[name]
            if code not in seen:
                found.append(code)
                seen.add(code)
            remaining = remaining.replace(name, " ")
    return found


# ─── 기존 헬퍼 ──────────────────────────────────────────────────────────────

def _extract_article_id(source_key: str, url: str, pattern: str) -> str:
    """원문 URL → '{source_key}_{numeric_id}' 형식의 article_id.

    source_key prefix 를 항상 붙여 다른 source 의 _id 와 충돌 방지.
    매칭 실패 시 URL 마지막 path segment fallback.
    """
    m = re.search(pattern, url)
    if m:
        return f"{source_key}_{m.group(1)}"
    tail = url.rstrip("/").split("/")[-1].split("?")[0]
    return f"{source_key}_{tail}"


def _parse_pub_date(raw: str) -> str:
    """RSS published 문자열 → 'YYYY-MM-DDTHH:MM:SS' (ISO).

    한경 RSS:    'Sun, 10 May 2026 18:24:34'           (RFC 2822)
    인포맥스 RSS: '2026-05-11 03:35:18'                 (ISO-ish)
    """
    if not raw:
        return ""
    raw = raw.strip()

    for fmt in ("%a, %d %b %Y %H:%M:%S %z", "%a, %d %b %Y %H:%M:%S"):
        try:
            dt = datetime.strptime(raw[:31].strip(), fmt)
            return dt.strftime("%Y-%m-%dT%H:%M:%S")
        except ValueError:
            continue
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S"):
        try:
            dt = datetime.strptime(raw[:19], fmt)
            return dt.strftime("%Y-%m-%dT%H:%M:%S")
        except ValueError:
            continue
    return raw[:19]


# ─── MongoDB 연결 ────────────────────────────────────────────────────────────

def _connect_mongo():
    """MongoDB modu_mongo.news_articles collection 반환. 실패 시 None."""
    if MongoClient is None:
        logger.error("pymongo 미설치 (pip install pymongo)")
        return None

    env_path = os.path.join(os.path.dirname(__file__), "../../.env")
    load_dotenv(dotenv_path=env_path)

    uri = os.getenv("MONGO_URI")
    if not uri:
        logger.error("MONGO_URI 환경변수 없음 — 적재 불가")
        return None

    try:
        client = MongoClient(uri, serverSelectionTimeoutMS=5000)
        client.admin.command("ping")
        atexit.register(client.close)   # 정상 종료 시 자동 cleanup
        logger.info("✓ MongoDB 연결 — modu_mongo.news_articles")
        return client["modu_mongo"]["news_articles"]
    except Exception as e:
        logger.error(f"MongoDB 연결 실패: {e}")
        return None


# ─── 핵심 크롤 로직 ──────────────────────────────────────────────────────────

def _fetch_article_body(session, url, selector):
    """원문 페이지에서 selector 로 본문 추출. 실패 시 None."""
    try:
        r = session.get(url, headers=HEADERS, timeout=HTTP_TIMEOUT)
        r.raise_for_status()
        soup = BeautifulSoup(r.text, "html.parser")
        body = soup.select_one(selector)
        if not body:
            return None
        # 광고 / 스크립트 제거
        for s in body.select("script, style, .ad, .advertisement"):
            s.decompose()
        return body.get_text(separator=" ", strip=True)
    except requests.RequestException as e:
        logger.warning(f"본문 fetch 실패 {url}: {e}")
        return None


def crawl_one_source(source: dict, collection, session) -> dict:
    """단일 source RSS 갱신 + 신규 기사 본문 적재.

    이미 MongoDB 에 _id 가 있는 기사는 본문 fetch 자체를 skip → 네트워크 절약.
    이번 사이클에서 매핑된 종목코드 set 도 함께 반환 — run_once 가 Redis sentiment
    갱신 대상 모음에 사용.

    Returns: {"fetched", "skipped", "failed", "touched_stocks": set[str]}
    """
    stats = {"fetched": 0, "skipped": 0, "failed": 0, "touched_stocks": set()}

    try:
        r = session.get(source["rss_url"], headers=HEADERS, timeout=HTTP_TIMEOUT)
        r.raise_for_status()
        feed = feedparser.parse(r.text)
    except requests.RequestException as e:
        logger.error(f"[{source['name']}] RSS 접근 실패: {e}")
        return stats

    logger.info(f"[{source['name']}] RSS {len(feed.entries)}건 응답")

    for entry in feed.entries:
        link = entry.link
        article_id = _extract_article_id(source["key"], link, source["id_pattern"])

        # 이미 적재된 기사 — 본문 fetch 자체 skip
        if collection.find_one({"_id": article_id}, projection={"_id": 1}):
            stats["skipped"] += 1
            continue

        content = _fetch_article_body(session, link, source["selector"])
        if not content:
            stats["failed"] += 1
            continue

        published_at = _parse_pub_date(entry.get("published", ""))
        date_ymd = published_at[:10].replace("-", "") if published_at else ""

        # FinBERT 감성 분석 (제목 + 본문). 첫 호출 시 모델 로드 (수 초 소요).
        try:
            sentiment = get_sentiment_analyzer().analyze(
                title=entry.title, content=content,
            )
        except Exception:
            logger.exception("sentiment 분석 실패 %s", article_id)
            sentiment = None

        # 본문에 등장한 종목코드 매핑 — engine 의 sentiment 집계 키.
        stock_codes = match_stocks((entry.title or "") + " " + (content or ""))

        doc = {
            "article_id":   article_id,
            "source":       source["key"],
            "source_name":  source["name"],
            "title":        entry.title,
            "content":      content,
            "summary":      content[:300],
            "published_at": published_at,
            "date":         date_ymd,
            "url":          link,
            "naver_url":    "",     # RSS 직접 source — Naver 경유 안 함
            "category":     "",
            "keywords":     list(getattr(entry, "tags", []) and
                                 [t.term for t in entry.tags] or []),
            "crawled_at":   datetime.now(timezone.utc),
            "sentiment":    sentiment,       # FinancialSentimentAnalyzer.analyze() 결과 또는 None
            "stock_codes":  stock_codes,     # 매핑된 종목 (없으면 [])
        }

        try:
            collection.update_one(
                {"_id": article_id},
                {"$set": doc},
                upsert=True,
            )
            stats["fetched"] += 1
            stats["touched_stocks"].update(stock_codes)
        except PyMongoError as e:
            logger.warning(f"MongoDB upsert 실패 {article_id}: {e}")
            stats["failed"] += 1

        time.sleep(ARTICLE_FETCH_DELAY)

    logger.info(
        f"[{source['name']}] fetched={stats['fetched']} / "
        f"skipped={stats['skipped']} / failed={stats['failed']}"
    )
    return stats


# ─── 종목별 sentiment 집계 → Redis ──────────────────────────────────────────

def _confidence_level(confidence: float) -> str:
    """confidence (0~100) → high / medium / low."""
    if confidence >= CONFIDENCE_HIGH:
        return "high"
    if confidence >= CONFIDENCE_MEDIUM:
        return "medium"
    return "low"


def update_sentiment_redis(collection, target_stock_codes: set[str]) -> int:
    """target_stock_codes 각 종목의 24h 안 기사 평균 sentiment → Redis SET.

    이번 cycle 에서 매핑된 종목만 갱신 — 매번 전체 종목 재계산하면 무거움.
    한 종목에 24h 기사가 0건이면 SET skip (자연 TTL 만료).
    """
    if not target_stock_codes:
        return 0

    cutoff = (datetime.now(KST) - timedelta(hours=SENTIMENT_LOOKBACK_HOURS)).strftime(
        "%Y-%m-%dT%H:%M:%S"
    )
    written = 0
    for stock_code in target_stock_codes:
        # 24h 안 그 종목 기사 + sentiment 분석된 것만
        docs = list(collection.find(
            {
                "stock_codes": stock_code,
                "published_at": {"$gte": cutoff},
                "sentiment": {"$exists": True, "$ne": None},
            },
            {"sentiment": 1},
        ))
        if not docs:
            continue

        n = len(docs)
        avg_score = sum(d["sentiment"]["sentiment_score"] for d in docs) / n
        avg_conf  = sum(d["sentiment"]["confidence"]      for d in docs) / n
        avg_pos   = sum(d["sentiment"]["pos_prob"]        for d in docs) / n
        avg_neu   = sum(d["sentiment"]["neu_prob"]        for d in docs) / n
        avg_neg   = sum(d["sentiment"]["neg_prob"]        for d in docs) / n

        payload = {
            "daily_score":      round(avg_score, 2),
            "confidence_level": _confidence_level(avg_conf),
            "pos_prob":         round(avg_pos, 2),
            "neu_prob":         round(avg_neu, 2),
            "neg_prob":         round(avg_neg, 2),
            "article_count":    n,
            "timestamp":        datetime.now(KST).isoformat(),
        }
        try:
            set_json(f"sentiment:{stock_code}", payload, ttl_seconds=SENTIMENT_REDIS_TTL)
            written += 1
        except Exception:
            logger.exception("redis SET failed for sentiment:%s", stock_code)

    logger.info("sentiment redis updated: %d/%d stocks (24h window)",
                written, len(target_stock_codes))
    return written


# ─── 진입점 ──────────────────────────────────────────────────────────────────

def run_once(collection=None) -> dict:
    """1회 RSS 갱신 + 매핑된 종목들 sentiment Redis 갱신.

    collection 미지정 시 내부에서 연결.
    """
    if collection is None:
        collection = _connect_mongo()
    if collection is None:
        return {"fetched": 0, "skipped": 0, "failed": 0, "sentiment_updated": 0}

    session = requests.Session()
    total = {"fetched": 0, "skipped": 0, "failed": 0}
    touched_stocks: set[str] = set()
    for source in SOURCES:
        s = crawl_one_source(source, collection, session)
        for k in total:
            total[k] += s[k]
        touched_stocks.update(s.get("touched_stocks", set()))

    # 이번 cycle 에 매핑된 종목들만 sentiment redis 갱신 — 24h window 평균.
    sentiment_updated = update_sentiment_redis(collection, touched_stocks)
    total["sentiment_updated"] = sentiment_updated

    logger.info(
        f"=== 1회 갱신 완료 — fetched={total['fetched']} / "
        f"skipped={total['skipped']} / failed={total['failed']} / "
        f"sentiment_updated={sentiment_updated} ==="
    )
    return total


def run_loop(interval: int = DEFAULT_LOOP_INTERVAL):
    """무한 루프 — interval 초마다 RSS 갱신.

    동일 collection 객체를 재사용해 connection pool 유지.
    """
    collection = _connect_mongo()
    if collection is None:
        return

    logger.info(f"=== news_collector 무한 루프 시작 — {interval}초 간격 ===")
    try:
        while True:
            try:
                run_once(collection)
            except Exception as e:
                logger.error(f"갱신 사이클 실패 (다음 사이클 계속): {e}")
            time.sleep(interval)
    except KeyboardInterrupt:
        logger.info("정지 요청 — 종료")


def run() -> None:
    """architecture 컨벤션상 진입점."""
    run_once()


def main():
    parser = argparse.ArgumentParser(description="실시간 뉴스 RSS 수집기 (한경·인포맥스)")
    parser.add_argument("--loop", action="store_true",
                        help="무한 루프 모드 (백그라운드 누적용)")
    parser.add_argument("--interval", type=int, default=DEFAULT_LOOP_INTERVAL,
                        help=f"루프 간격 초 (기본: {DEFAULT_LOOP_INTERVAL})")
    args = parser.parse_args()

    if args.loop:
        run_loop(args.interval)
    else:
        run_once()


if __name__ == "__main__":
    main()
