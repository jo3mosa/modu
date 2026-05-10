"""
네이버 금융 뉴스 크롤러 - Selenium 버전
requests가 차단될 때 사용. 실제 브라우저로 동작해서 차단 우회에 강함.

설치:
    pip install selenium webdriver-manager beautifulsoup4

사용법:
    python naver_news_crawler_selenium.py --start 2023-01-01 --end 2023-01-31 --output ./data
    python naver_news_crawler_selenium.py --start 2023-01-01 --end 2024-12-31 --output ./data --headless
"""

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
from pathlib import Path
from typing import Optional

from dotenv import load_dotenv
try:
    from pymongo import MongoClient
    from pymongo.errors import PyMongoError
except ImportError:
    MongoClient = None
    PyMongoError = Exception

from selenium import webdriver
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.chrome.service import Service
from selenium.webdriver.common.by import By
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from selenium.common.exceptions import TimeoutException, NoSuchElementException
from webdriver_manager.chrome import ChromeDriverManager
from bs4 import BeautifulSoup

# ─── 설정 ────────────────────────────────────────────────────────────────────

SOURCES = {
    "hankyung": {"id": "015", "name": "한국경제"},
    "infomax":  {"id": "013", "name": "연합인포맥스"},
}

DELAY_MIN = 1.0
DELAY_MAX = 2.5

# 한 날짜 당 페이지 안전 cap — 빈 페이지가 정상 종료 신호이며 이 값은 fail-safe.
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
    source: str
    source_name: str
    title: str
    content: str
    summary: str
    published_at: str
    date: str
    url: str
    naver_url: str
    category: str
    keywords: list


# ─── 진행 상태 영속화 헬퍼 ────────────────────────────────────────────────────

def _atomic_write_json(path: Path, data) -> None:
    """원자적 JSON 저장 — temp 파일에 쓴 뒤 os.replace 로 교체."""
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


# ─── Selenium 크롤러 ──────────────────────────────────────────────────────────

class NaverNewsSeleniumCrawler:
    def __init__(self, headless: bool = True, delay_min=DELAY_MIN, delay_max=DELAY_MAX,
                 mongo_collection=None):
        self.delay_min = delay_min
        self.delay_max = delay_max
        # MongoDB collection (옵션). None 이면 JSON/CSV 만 적재.
        self.mongo_collection = mongo_collection
        self.driver = self._init_driver(headless)

    def _init_driver(self, headless: bool) -> webdriver.Chrome:
        options = Options()
        if headless:
            options.add_argument("--headless=new")
        options.add_argument("--no-sandbox")
        options.add_argument("--disable-dev-shm-usage")
        options.add_argument("--disable-blink-features=AutomationControlled")
        options.add_experimental_option("excludeSwitches", ["enable-automation"])
        options.add_experimental_option("useAutomationExtension", False)
        options.add_argument(
            "user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/120.0.0.0 Safari/537.36"
        )
        options.add_argument("--window-size=1280,900")

        service = Service(ChromeDriverManager().install())
        driver = webdriver.Chrome(service=service, options=options)

        # 자동화 탐지 우회
        driver.execute_cdp_cmd(
            "Page.addScriptToEvaluateOnNewDocument",
            {"source": "Object.defineProperty(navigator, 'webdriver', {get: () => undefined})"}
        )
        return driver

    def _sleep(self, extra: float = 0):
        time.sleep(random.uniform(self.delay_min, self.delay_max) + extra)

    def _get_soup(self, url: str, wait_selector: str = None, timeout: int = 10) -> Optional[BeautifulSoup]:
        try:
            self.driver.get(url)
            if wait_selector:
                WebDriverWait(self.driver, timeout).until(
                    EC.presence_of_element_located((By.CSS_SELECTOR, wait_selector))
                )
            else:
                time.sleep(1.5)
            return BeautifulSoup(self.driver.page_source, "html.parser")
        except TimeoutException:
            logger.warning(f"타임아웃: {url}")
            return BeautifulSoup(self.driver.page_source, "html.parser")
        except Exception as e:
            logger.warning(f"페이지 로드 실패: {url} → {e}")
            return None

    # ── 목록 수집 ──────────────────────────────────────────────────────────────

    def get_article_list(self, source_id: str, date: str, page: int = 1) -> list[dict]:
        url = (
            f"https://news.naver.com/main/list.naver"
            f"?mode=LPOD&mid=sec&oid={source_id}&date={date}&page={page}"
        )
        soup = self._get_soup(url, wait_selector="ul.type06, ul.type06_headline")
        if not soup:
            return []

        articles = []
        for ul in soup.select("ul.type06_headline, ul.type06"):
            for li in ul.select("li"):
                a_tag = li.select_one("dl > dt:not(.photo) > a") or li.select_one("dt > a")
                if not a_tag:
                    continue

                title = a_tag.get_text(strip=True)
                naver_url = a_tag.get("href", "")
                if not naver_url or "article" not in naver_url:
                    continue

                article_id = self._extract_article_id(naver_url)
                category_tag = li.select_one("dd.article_info a")
                category = category_tag.get_text(strip=True) if category_tag else ""

                articles.append({
                    "article_id": article_id,
                    "title": title,
                    "naver_url": naver_url,
                    "category": category,
                    "date": date,
                })

        return articles

    def _extract_article_id(self, url: str) -> str:
        # 레거시 URL: ...?oid=015&aid=0004931791
        match = re.search(r"oid=(\d+).*?aid=(\d+)", url)
        if match:
            return f"{match.group(1)}_{match.group(2)}"
        # 신규 모바일 URL: .../article/015/0004931791  (또는 /mnews/article/...)
        match = re.search(r"/article/(\d+)/(\d+)", url)
        if match:
            return f"{match.group(1)}_{match.group(2)}"
        return url.split("/")[-1]

    # ── 본문 수집 ──────────────────────────────────────────────────────────────

    def get_article_content(self, naver_url: str) -> dict:
        soup = self._get_soup(naver_url, wait_selector="#newsct_article, #articeBody")
        if not soup:
            return {}

        result = {"content": "", "published_at": "", "original_url": "", "keywords": []}

        # 본문
        for sel in ["#newsct_article", "#articeBody", "#article-view-content-div", ".news_end"]:
            tag = soup.select_one(sel)
            if tag:
                for s in tag.select("script, style, .ad"):
                    s.decompose()
                result["content"] = tag.get_text(separator="\n", strip=True)
                break

        # 발행시각
        for sel in [
            "span.media_end_head_info_datestamp_time[data-date-time]",
            "span._ARTICLE_DATE_TIME",
            ".article_info em",
            "time",
        ]:
            tag = soup.select_one(sel)
            if tag:
                raw = tag.get("data-date-time") or tag.get_text(strip=True)
                result["published_at"] = self._parse_datetime(raw)
                break

        # 원문 URL
        orig = soup.select_one("a.media_end_head_origin_link")
        if orig:
            result["original_url"] = orig.get("href", "")

        # 키워드
        meta = soup.select_one('meta[name="keywords"]')
        if meta:
            result["keywords"] = [k.strip() for k in meta.get("content", "").split(",") if k.strip()]

        return result

    def _parse_datetime(self, raw: str) -> str:
        if not raw:
            return ""
        if "T" in raw and len(raw) >= 16:
            return raw[:19]
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
                logger.info(f"[{idx}/{total_dates}] {date} 건너뜀")
                continue

            logger.info(f"[{idx}/{total_dates}] {source_name} {date} 수집 시작")
            day_articles = []
            date_complete = False   # 정상 종료 시에만 True → progress 갱신 조건

            try:
                # 페이지 수를 미리 추정하지 않고 빈 페이지 도달 시 종료.
                # 네이버 페이저는 10p 단위 표시라 get_total_pages 가 11+ 페이지를 누락시킴.
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
                        pub_at = content_data.get("published_at", "") or \
                                 f"{date[:4]}-{date[4:6]}-{date[6:]}T00:00:00"

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

            if day_articles:
                day_file = output_path / f"{source_key}_{date}.json"
                with open(day_file, "w", encoding="utf-8") as f:
                    json.dump([asdict(a) for a in day_articles], f, ensure_ascii=False, indent=2)
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

    def close(self):
        self.driver.quit()


# ─── 저장 유틸 ────────────────────────────────────────────────────────────────

def save_to_csv(articles: list[NewsArticle], path: str):
    if not articles:
        return
    with open(path, "w", newline="", encoding="utf-8-sig") as f:
        writer = csv.DictWriter(f, fieldnames=asdict(articles[0]).keys())
        writer.writeheader()
        for a in articles:
            row = asdict(a)
            row["keywords"] = "|".join(row["keywords"])
            writer.writerow(row)
    logger.info(f"CSV 저장 완료: {path} ({len(articles)}건)")


def merge_daily_jsons(output_dir: str, source_key: str, out_file: str):
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
    parser = argparse.ArgumentParser(description="네이버 뉴스 Selenium 크롤러")
    parser.add_argument("--start",     required=True)
    parser.add_argument("--end",       required=True)
    parser.add_argument("--output",    default="./news_data")
    parser.add_argument("--sources",   default="all", help="all | hankyung | infomax")
    parser.add_argument("--headless",  action="store_true", help="헤드리스 모드 (기본: 브라우저 표시)")
    parser.add_argument("--no-resume", action="store_true")
    parser.add_argument("--merge",     action="store_true")
    parser.add_argument("--no-mongo",  action="store_true",
                        help="MongoDB 동시 적재 비활성화 (JSON/CSV 만 저장)")
    args = parser.parse_args()

    source_keys = list(SOURCES.keys()) if args.sources == "all" else [args.sources]
    resume = not args.no_resume

    mongo_collection = None if args.no_mongo else _connect_mongo()

    crawler = NaverNewsSeleniumCrawler(
        headless=args.headless,
        mongo_collection=mongo_collection,
    )

    try:
        for source_key in source_keys:
            logger.info(f"=== {SOURCES[source_key]['name']} 수집 시작 ({args.start} ~ {args.end}) ===")

            articles = crawler.crawl_date_range(
                source_key=source_key,
                start_date=args.start,
                end_date=args.end,
                output_dir=args.output,
                resume=resume,
            )

            csv_path = os.path.join(args.output, f"{source_key}_{args.start}_{args.end}.csv")
            save_to_csv(articles, csv_path)

            if args.merge:
                merge_daily_jsons(args.output, source_key, f"{args.output}/{source_key}_merged.json")

            logger.info(f"=== {SOURCES[source_key]['name']} 완료: {len(articles)}건 ===\n")
    finally:
        crawler.close()


if __name__ == "__main__":
    main()