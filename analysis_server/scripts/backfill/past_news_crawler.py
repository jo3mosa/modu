"""
네이버 금융 뉴스 크롤러
- 대상: 한국경제(015), 연합인포맥스(013) 
- 방식: 네이버 뉴스 아카이브 날짜별 크롤링
- 출력: JSON / CSV

사용법:
    python naver_news_crawler.py --start 2023-01-01 --end 2024-12-31 --output ./data
    python naver_news_crawler.py --start 2023-01-01 --end 2024-12-31 --output ./data --sources hankyung
"""

import requests
from bs4 import BeautifulSoup
import json
import csv
import time
import random
import logging
import argparse
import os
import re
import tempfile
from datetime import datetime, timedelta, timezone
from dataclasses import dataclass, asdict
from typing import Optional
from pathlib import Path

from dotenv import load_dotenv
try:
    from pymongo import MongoClient
    from pymongo.errors import PyMongoError
except ImportError:
    MongoClient = None
    PyMongoError = Exception

# ─── 설정 ────────────────────────────────────────────────────────────────────

# 네이버 언론사 코드
SOURCES = {
    "hankyung":  {"id": "015", "name": "한국경제"},
    "infomax":   {"id": "013", "name": "연합인포맥스"},
}

# 요청 헤더 (봇 차단 우회)
HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/120.0.0.0 Safari/537.36"
    ),
    "Referer": "https://news.naver.com/",
    "Accept-Language": "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
}

# 딜레이 설정 (초) - 너무 빠르면 차단됨
DELAY_MIN = 0.8
DELAY_MAX = 1.8

# 한 날짜 당 페이지 안전 cap — 페이지 수가 비정상적으로 많을 때 무한 루프 방지.
# 빈 페이지 도달이 정상 종료 신호이며, 이 cap 은 어디까지나 fail-safe.
MAX_PAGES_PER_DAY = 100

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
logger = logging.getLogger(__name__)


# ─── 데이터 모델 ──────────────────────────────────────────────────────────────

@dataclass
class NewsArticle:
    article_id: str
    source: str          # hankyung / infomax
    source_name: str     # 한국경제 / 연합인포맥스
    title: str
    content: str
    summary: str         # 본문 앞 300자
    published_at: str    # ISO 형식 (2023-01-15T09:30:00)
    date: str            # YYYYMMDD
    url: str
    naver_url: str
    category: str        # 경제/증권/부동산 등
    keywords: list       # 추출 키워드


# ─── 진행 상태 영속화 헬퍼 ────────────────────────────────────────────────────

def _atomic_write_json(path: Path, data) -> None:
    """원자적 JSON 저장 — temp 파일에 쓴 뒤 os.replace 로 교체.

    저장 도중 프로세스가 죽어도 기존 파일은 손상되지 않음 (temp 만 잘림).
    Windows/POSIX 모두 os.replace 는 같은 파일시스템 안에서 원자적 동작.
    """
    fd, tmp_path = tempfile.mkstemp(dir=str(path.parent), suffix=".tmp", text=True)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False)
        os.replace(tmp_path, str(path))
    except Exception:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass
        raise


def _load_progress(path: Path) -> set:
    """progress.json 안전 로드. 손상된 파일이면 빈 set 반환 (처음부터 재시작)."""
    if not path.exists():
        return set()
    try:
        with open(path, encoding="utf-8") as f:
            return set(json.load(f).get("completed", []))
    except (json.JSONDecodeError, OSError) as e:
        logger.warning(f"progress 손상 — 처음부터 재시작: {path.name} ({e})")
        return set()


# ─── 크롤러 ───────────────────────────────────────────────────────────────────

class NaverNewsCrawler:
    def __init__(self, delay_min=DELAY_MIN, delay_max=DELAY_MAX, mongo_collection=None):
        self.session = requests.Session()
        self.session.headers.update(HEADERS)
        self.delay_min = delay_min
        self.delay_max = delay_max
        # MongoDB collection (옵션). None 이면 JSON/CSV 만 적재.
        self.mongo_collection = mongo_collection

    def _sleep(self):
        time.sleep(random.uniform(self.delay_min, self.delay_max))

    def _get(self, url: str, params: dict = None) -> Optional[BeautifulSoup]:
        """GET 요청 + BeautifulSoup 반환. 실패 시 None."""
        try:
            resp = self.session.get(url, params=params, timeout=10)
            resp.raise_for_status()
            return BeautifulSoup(resp.text, "html.parser")
        except requests.RequestException as e:
            logger.warning(f"요청 실패: {url} → {e}")
            return None

    # ── 목록 수집 ──────────────────────────────────────────────────────────────

    def get_article_list(self, source_id: str, date: str, page: int = 1) -> list[dict]:
        """
        네이버 뉴스 언론사별 날짜 아카이브에서 기사 목록 수집
        URL: https://news.naver.com/main/list.naver?mode=LPOD&mid=sec&oid={source_id}&date={date}&page={page}
        """
        url = "https://news.naver.com/main/list.naver"
        params = {
            "mode": "LPOD",
            "mid": "sec",
            "oid": source_id,
            "date": date,
            "page": page,
        }

        soup = self._get(url, params)
        if not soup:
            return []

        articles = []
        # 뉴스 목록 파싱 (ul.type06_headline + ul.type06)
        for ul in soup.select("ul.type06_headline, ul.type06"):
            for li in ul.select("li"):
                a_tag = li.select_one("dl > dt:not(.photo) > a") or li.select_one("dt > a")
                if not a_tag:
                    continue

                title = a_tag.get_text(strip=True)
                naver_url = a_tag.get("href", "")
                if not naver_url or "article" not in naver_url:
                    continue

                # 기사 ID 추출 (oid/aid)
                article_id = self._extract_article_id(naver_url)

                # 날짜/시간 (dd 태그)
                dd_tag = li.select_one("dd.article_info span") or li.select_one(".writing")
                pub_time = dd_tag.get_text(strip=True) if dd_tag else ""

                # 카테고리 (섹션 정보)
                category_tag = li.select_one("dd.article_info a")
                category = category_tag.get_text(strip=True) if category_tag else ""

                articles.append({
                    "article_id": article_id,
                    "title": title,
                    "naver_url": naver_url,
                    "pub_time_raw": pub_time,
                    "category": category,
                    "date": date,
                })

        return articles

    def _extract_article_id(self, url: str) -> str:
        """URL에서 oid_aid 형태의 고유 ID 추출.

        - 레거시 URL: ...?oid=015&aid=0004931791
        - 신규 모바일 URL: .../article/015/0004931791  (또는 /mnews/article/...)
        """
        match = re.search(r"oid=(\d+).*?aid=(\d+)", url)
        if match:
            return f"{match.group(1)}_{match.group(2)}"
        match = re.search(r"/article/(\d+)/(\d+)", url)
        if match:
            return f"{match.group(1)}_{match.group(2)}"
        return url.split("/")[-1]

    # ── 본문 수집 ──────────────────────────────────────────────────────────────

    def get_article_content(self, naver_url: str) -> dict:
        """기사 본문, 발행시각, 원문 URL 수집"""
        soup = self._get(naver_url)
        if not soup:
            return {}

        result = {
            "content": "",
            "published_at": "",
            "original_url": "",
            "keywords": [],
        }

        # 본문 (네이버 뉴스 본문 선택자)
        content_selectors = [
            "#newsct_article",          # 일반 뉴스
            "#articeBody",              # 구버전
            "#article-view-content-div",
            ".news_end",
        ]
        for sel in content_selectors:
            tag = soup.select_one(sel)
            if tag:
                # 광고/스크립트 제거
                for s in tag.select("script, style, .ad, .advertisement"):
                    s.decompose()
                result["content"] = tag.get_text(separator="\n", strip=True)
                break

        # 발행시각
        time_selectors = [
            "span.media_end_head_info_datestamp_time",
            ".article_info em",
            "span._ARTICLE_DATE_TIME",
            "time",
        ]
        for sel in time_selectors:
            tag = soup.select_one(sel)
            if tag:
                raw = tag.get("data-date-time") or tag.get_text(strip=True)
                result["published_at"] = self._parse_datetime(raw)
                break

        # 원문 URL
        original_link = soup.select_one("a.media_end_head_origin_link") or soup.select_one("a[class*='original']")
        if original_link:
            result["original_url"] = original_link.get("href", "")

        # 키워드 (메타태그)
        meta_keywords = soup.select_one('meta[name="keywords"]')
        if meta_keywords:
            kw_str = meta_keywords.get("content", "")
            result["keywords"] = [k.strip() for k in kw_str.split(",") if k.strip()]

        return result

    def _parse_datetime(self, raw: str) -> str:
        """다양한 날짜 형식을 ISO 형식으로 변환"""
        if not raw:
            return ""
        # 이미 ISO 형식
        if "T" in raw and len(raw) >= 16:
            return raw[:19]
        # "2023.01.15. 오전 09:30" 형식
        match = re.search(r"(\d{4})\.(\d{2})\.(\d{2})\.\s*(오전|오후)?\s*(\d{1,2}):(\d{2})", raw)
        if match:
            y, mo, d, ampm, h, mi = match.groups()
            h = int(h)
            if ampm == "오후" and h < 12:
                h += 12
            elif ampm == "오전" and h == 12:
                h = 0
            return f"{y}-{mo}-{d}T{h:02d}:{mi}:00"
        return raw

    # ── 메인 수집 루프 ─────────────────────────────────────────────────────────

    def crawl_date_range(
        self,
        source_key: str,
        start_date: str,
        end_date: str,
        output_dir: str,
        resume: bool = True,
    ) -> list[NewsArticle]:
        """
        날짜 범위 전체 크롤링
        - source_key: "hankyung" or "infomax"
        - start_date / end_date: "YYYY-MM-DD"
        - output_dir: 결과 저장 디렉토리
        - resume: True면 이미 수집된 날짜 건너뜀
        """
        source = SOURCES[source_key]
        source_id = source["id"]
        source_name = source["name"]

        output_path = Path(output_dir)
        output_path.mkdir(parents=True, exist_ok=True)

        # 진행 상황 추적 파일 — 손상된 파일이면 빈 set 으로 fallback
        progress_file = output_path / f"{source_key}_progress.json"
        completed_dates = _load_progress(progress_file) if resume else set()
        if completed_dates:
            logger.info(f"이어하기 모드: {len(completed_dates)}일 이미 완료")

        # 날짜 범위 생성
        start = datetime.strptime(start_date, "%Y-%m-%d")
        end = datetime.strptime(end_date, "%Y-%m-%d")
        dates = []
        cur = start
        while cur <= end:
            dates.append(cur.strftime("%Y%m%d"))
            cur += timedelta(days=1)

        all_articles = []
        total_dates = len(dates)

        for idx, date in enumerate(dates, 1):
            if date in completed_dates:
                logger.info(f"[{idx}/{total_dates}] {date} 건너뜀 (완료)")
                continue

            logger.info(f"[{idx}/{total_dates}] {source_name} {date} 수집 시작")
            day_articles = []
            date_complete = False   # 정상 종료 시에만 True → progress 갱신 조건

            try:
                # 페이지 수를 미리 추정하지 않고 빈 페이지 도달 시 종료.
                # 네이버 페이저는 10p 단위로 끊어 보여서 get_total_pages 가 11+ 페이지를
                # 누락시키는 문제가 있었음.
                page = 1
                while True:
                    if page > MAX_PAGES_PER_DAY:
                        logger.warning(
                            f"{date} MAX_PAGES_PER_DAY({MAX_PAGES_PER_DAY}) 도달 — cap 적용"
                        )
                        break

                    article_list = self.get_article_list(source_id, date, page)
                    self._sleep()

                    if not article_list:
                        # 빈 페이지 = 더 이상 기사 없음 (정상 종료)
                        break

                    for art_meta in article_list:
                        content_data = self.get_article_content(art_meta["naver_url"])
                        self._sleep()

                        content = content_data.get("content", "")
                        pub_at = content_data.get("published_at", "") or f"{date[:4]}-{date[4:6]}-{date[6:]}T00:00:00"

                        article = NewsArticle(
                            article_id=art_meta["article_id"],
                            source=source_key,
                            source_name=source_name,
                            title=art_meta["title"],
                            content=content,
                            summary=content[:300] if content else "",
                            published_at=pub_at,
                            date=date,
                            url=content_data.get("original_url", ""),
                            naver_url=art_meta["naver_url"],
                            category=art_meta.get("category", ""),
                            keywords=content_data.get("keywords", []),
                        )
                        day_articles.append(article)

                        # MongoDB 동시 적재 — 실패해도 JSON 저장은 계속 (안전망)
                        if self.mongo_collection is not None:
                            try:
                                self.mongo_collection.update_one(
                                    {"_id": article.article_id},
                                    {"$set": {**asdict(article),
                                              "crawled_at": datetime.now(timezone.utc)}},
                                    upsert=True,
                                )
                            except PyMongoError as e:
                                logger.warning(
                                    f"MongoDB upsert 실패 ({article.article_id}): {e}"
                                )

                    logger.info(f"  └ 페이지 {page} 완료 ({len(article_list)}건)")
                    page += 1

                date_complete = True   # try 블록 정상 종료 시에만 도달

            except Exception as e:
                logger.error(f"{date} 수집 중 오류: {e}")
                # 부분 수집분은 JSON 으로 저장하되 progress 는 갱신하지 않음
                # → 다음 실행에서 자동 재시도

            # 날짜별 JSON 저장 — 부분 데이터라도 보존
            if day_articles:
                day_file = output_path / f"{source_key}_{date}.json"
                with open(day_file, "w", encoding="utf-8") as f:
                    json.dump(
                        [asdict(a) for a in day_articles],
                        f, ensure_ascii=False, indent=2
                    )
                logger.info(f"  └ {len(day_articles)}건 저장 → {day_file.name}")

            all_articles.extend(day_articles)

            # 진행 상황 업데이트 — 정상 종료된 경우에만 (atomic write)
            if date_complete:
                completed_dates.add(date)
                _atomic_write_json(progress_file, {"completed": sorted(completed_dates)})
            else:
                logger.warning(
                    f"{date} 부분 수집 — progress 갱신 안 함, 다음 실행에서 재시도됨"
                )

        return all_articles


# ─── 저장 유틸 ────────────────────────────────────────────────────────────────

def save_to_csv(articles: list[NewsArticle], path: str):
    if not articles:
        return
    with open(path, "w", newline="", encoding="utf-8-sig") as f:
        writer = csv.DictWriter(f, fieldnames=asdict(articles[0]).keys())
        writer.writeheader()
        for a in articles:
            row = asdict(a)
            row["keywords"] = "|".join(row["keywords"])  # 리스트 → 문자열
            writer.writerow(row)
    logger.info(f"CSV 저장 완료: {path} ({len(articles)}건)")


def merge_daily_jsons(output_dir: str, source_key: str, out_file: str):
    """날짜별 JSON 파일을 하나로 합침"""
    output_path = Path(output_dir)
    all_articles = []
    for f in sorted(output_path.glob(f"{source_key}_????????.json")):
        with open(f, encoding="utf-8") as fp:
            all_articles.extend(json.load(fp))
    with open(out_file, "w", encoding="utf-8") as fp:
        json.dump(all_articles, fp, ensure_ascii=False, indent=2)
    logger.info(f"병합 완료: {out_file} (총 {len(all_articles)}건)")
    return all_articles


# ─── CLI ──────────────────────────────────────────────────────────────────────

def parse_args():
    parser = argparse.ArgumentParser(description="네이버 금융 뉴스 크롤러 (한국경제/연합인포맥스)")
    parser.add_argument("--start",   required=True,  help="시작일 (YYYY-MM-DD)")
    parser.add_argument("--end",     required=True,  help="종료일 (YYYY-MM-DD)")
    parser.add_argument("--output",  default="./news_data", help="출력 디렉토리 (기본: ./news_data)")
    parser.add_argument("--sources", default="all",
                        help="수집 언론사: all | hankyung | infomax (기본: all)")
    parser.add_argument("--no-resume", action="store_true", help="이어하기 비활성화 (처음부터 재수집)")
    parser.add_argument("--delay",  type=float, default=None,
                        help="요청 간 딜레이 (초, 기본: 0.8~1.8 랜덤)")
    parser.add_argument("--merge",  action="store_true", help="수집 완료 후 날짜별 JSON을 하나로 합침")
    parser.add_argument("--no-mongo", action="store_true",
                        help="MongoDB 동시 적재 비활성화 (JSON/CSV 만 저장)")
    return parser.parse_args()


def _connect_mongo():
    """MongoDB modu_mongo.news_articles 연결. 실패 시 None 반환 (JSON 만으로 진행)."""
    if MongoClient is None:
        logger.warning("pymongo 미설치 — JSON 만 저장합니다 (pip install pymongo)")
        return None

    # pipelines/news/ → modu/ 루트로 3단계 상위
    env_path = os.path.join(os.path.dirname(__file__), "../../../.env")
    load_dotenv(dotenv_path=env_path)

    uri = os.getenv("MONGO_URI")
    if not uri:
        logger.warning("MONGO_URI 환경변수 없음 — JSON 만 저장합니다")
        return None

    try:
        client = MongoClient(uri, serverSelectionTimeoutMS=5000)
        client.admin.command("ping")
        collection = client["modu_mongo"]["news_articles"]
        logger.info("✓ MongoDB 연결 — modu_mongo.news_articles 에 동시 적재")
        return collection
    except Exception as e:
        logger.warning(f"MongoDB 연결 실패 — JSON 만 저장: {e}")
        return None


def main():
    args = parse_args()

    source_keys = list(SOURCES.keys()) if args.sources == "all" else [args.sources]
    resume = not args.no_resume

    delay_min = args.delay if args.delay else DELAY_MIN
    delay_max = args.delay * 1.5 if args.delay else DELAY_MAX

    mongo_collection = None if args.no_mongo else _connect_mongo()

    crawler = NaverNewsCrawler(
        delay_min=delay_min,
        delay_max=delay_max,
        mongo_collection=mongo_collection,
    )

    for source_key in source_keys:
        logger.info(f"=== {SOURCES[source_key]['name']} 수집 시작 ({args.start} ~ {args.end}) ===")

        articles = crawler.crawl_date_range(
            source_key=source_key,
            start_date=args.start,
            end_date=args.end,
            output_dir=args.output,
            resume=resume,
        )

        # CSV 저장
        csv_path = os.path.join(args.output, f"{source_key}_{args.start}_{args.end}.csv")
        save_to_csv(articles, csv_path)

        # 병합 옵션
        if args.merge:
            merged_path = os.path.join(args.output, f"{source_key}_merged.json")
            merge_daily_jsons(args.output, source_key, merged_path)

        logger.info(f"=== {SOURCES[source_key]['name']} 완료: 총 {len(articles)}건 ===\n")


if __name__ == "__main__":
    main()