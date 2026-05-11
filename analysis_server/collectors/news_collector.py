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
import time
from datetime import datetime, timezone

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

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
logger = logging.getLogger(__name__)


# ─── 헬퍼 ────────────────────────────────────────────────────────────────────

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
    Returns: {"fetched", "skipped", "failed"}
    """
    stats = {"fetched": 0, "skipped": 0, "failed": 0}

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
        }

        try:
            collection.update_one(
                {"_id": article_id},
                {"$set": doc},
                upsert=True,
            )
            stats["fetched"] += 1
        except PyMongoError as e:
            logger.warning(f"MongoDB upsert 실패 {article_id}: {e}")
            stats["failed"] += 1

        time.sleep(ARTICLE_FETCH_DELAY)

    logger.info(
        f"[{source['name']}] fetched={stats['fetched']} / "
        f"skipped={stats['skipped']} / failed={stats['failed']}"
    )
    return stats


# ─── 진입점 ──────────────────────────────────────────────────────────────────

def run_once(collection=None) -> dict:
    """1회 RSS 갱신. collection 미지정 시 내부에서 연결."""
    if collection is None:
        collection = _connect_mongo()
    if collection is None:
        return {"fetched": 0, "skipped": 0, "failed": 0}

    session = requests.Session()
    total = {"fetched": 0, "skipped": 0, "failed": 0}
    for source in SOURCES:
        s = crawl_one_source(source, collection, session)
        for k in total:
            total[k] += s[k]

    logger.info(
        f"=== 1회 갱신 완료 — fetched={total['fetched']} / "
        f"skipped={total['skipped']} / failed={total['failed']} ==="
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
