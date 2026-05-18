"""disclosure_timestamp_backfill (v2 — 날짜별 검색 페이지 스크래핑)

dart.fss.or.kr/dsac001/mainY.do 일자별 검색 결과 페이지에서
(rcept_no, 시각) 쌍을 일괄 추출해 MongoDB `modu_mongo.disclosures.rcept_datetime` 보강.

배경:
    OpenDART list.json 은 접수 시각(HH:MM) 미제공. 백필된 `rcept_date` 는 KST 자정 default.
    백테스트에서 "장중 공시 vs 장 마감 후 공시" 구분에 분 단위 시각 필요.

    v1 은 건별 dsaf001/main.do 상세 페이지를 fetch 했으나 그 페이지엔 시각 정보가 없음
    (자료 인덱스 페이지). 검색 결과 페이지에는 표 형식으로 시각이 있어 일자 단위로
    수백 건을 한 번에 수집 가능.

저장 정책:
    - 기존 `rcept_date` (KST 자정) 유지 — 라이브 collector·기존 쿼리 영향 0.
    - 새 필드 `rcept_datetime` 으로 분 단위 시각 추가.
    - 매칭은 `_id = rcept_no` 로. 검색 페이지에 보이는 rcept_no 가 Mongo 에 없으면 skip
      (펀드/자산운용 등 backfill 누락분이면 자연스럽게 무시).

페이징:
    검색 페이지는 한 페이지당 maxResults 건. 빈 페이지(표 행 없음) 나올 때까지 progressive.
    명시적 total_page 표기를 파싱해도 되지만 페이지 구조 변경 위험이 있어 EOF 감지로 단순화.

성능:
    730일 × 평균 ~10페이지 × 0.5초 ≈ 60-90분. v1 대비 ~15배 단축.

사용법:
    # repo 루트에서 venv 활성화 후, analysis_server 디렉토리에서
    python -m scripts.backfill.disclosure_timestamp_backfill
    python -m scripts.backfill.disclosure_timestamp_backfill --start 2024-01-02 --end 2024-01-02 --dry-run   # 하루치 검증
    python -m scripts.backfill.disclosure_timestamp_backfill --max-pages 2 --dry-run   # 일자당 페이지 상한
"""

import argparse
import logging
import os
import re
import sys
import time
from datetime import date, datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

import requests
from bs4 import BeautifulSoup
from dotenv import load_dotenv
from pymongo import MongoClient, UpdateOne
from pymongo.errors import PyMongoError

load_dotenv(dotenv_path=Path(__file__).resolve().parents[2].parent / ".env")

logger = logging.getLogger(__name__)

KST = ZoneInfo("Asia/Seoul")

DB_NAME = "modu_mongo"
DISCLOSURE_COLL = "disclosures"
STATE_COLL = "backfill_state"
STATE_ID = "disclosure_timestamp_backfill_v2"

SEARCH_URL = "https://dart.fss.or.kr/dsac001/search.ax"
# mainY=유가증권, mainK=코스닥, mainAll=전체. 백테스트는 시장 무관 전 종목 대상.
MAIN_URL = "https://dart.fss.or.kr/dsac001/mainAll.do"

HTTP_TIMEOUT = 15
HTTP_RETRIES = 3
USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/121.0 Safari/537.36"
)

DEFAULT_SLEEP = 0.4              # 페이지간 sleep
DEFAULT_MAX_RESULTS = 100        # 페이지 크기
DEFAULT_MAX_PAGES_PER_DAY = 100  # safety cap

DEFAULT_START_DATE = "2023-01-01"
DEFAULT_END_DATE = "2024-12-31"

# 행 단위 추출용 정규식.
TIME_RE = re.compile(r"\b(\d{1,2}):(\d{2})\b")
RCEPT_NO_RE = re.compile(r"\b(\d{14})\b")  # YYYYMMDDxxxxxx 14자리


def _parse_date(s: str) -> date:
    return datetime.strptime(s, "%Y-%m-%d").date()


def _yyyymmdd(d: date) -> str:
    return d.strftime("%Y%m%d")


def _connect_mongo():
    uri = os.getenv("MONGO_URI")
    if not uri:
        logger.error("MONGO_URI 환경변수 없음")
        sys.exit(1)
    try:
        client = MongoClient(
            uri,
            serverSelectionTimeoutMS=30000,
            socketTimeoutMS=30000,
            connectTimeoutMS=30000,
            retryWrites=True,
        )
        client.admin.command("ping")
    except Exception as e:
        logger.error("MongoDB 연결 실패: %s", e)
        sys.exit(1)
    db = client[DB_NAME]
    logger.info("✓ MongoDB 연결 — %s", DB_NAME)
    return client, db[DISCLOSURE_COLL], db[STATE_COLL]


def _retry_mongo(op, label: str, attempts: int = 3):
    last_exc = None
    for i in range(attempts):
        try:
            return op()
        except PyMongoError as e:
            last_exc = e
            wait = 2 ** i
            logger.warning("%s 실패 (%d/%d): %s — %ds 후 재시도",
                           label, i + 1, attempts, e, wait)
            time.sleep(wait)
    logger.error("%s 최종 실패 — %s", label, last_exc)
    raise last_exc


def _fetch_page_html(session: requests.Session, day: date,
                     current_page: int, max_results: int) -> str | None:
    """검색 결과 페이지 HTML.

    mainY.do 는 사람용 페이지지만 selectDate + currentPage + maxResults 만 주면
    표 데이터를 정적 HTML 로 그대로 렌더해서 돌려준다.
    """
    params = {
        "selectDate": _yyyymmdd(day),
        "currentPage": str(current_page),
        "maxResults": str(max_results),
        "mdayCnt": "0",
    }
    for attempt in range(HTTP_RETRIES):
        try:
            resp = session.get(MAIN_URL, params=params, timeout=HTTP_TIMEOUT)
            resp.raise_for_status()
            return resp.text
        except requests.RequestException as e:
            wait = 2 ** attempt
            logger.warning("HTTP 실패 day=%s page=%d (%d/%d): %s — %ds 후 재시도",
                           day, current_page, attempt + 1, HTTP_RETRIES, e, wait)
            time.sleep(wait)
    logger.warning("HTTP 최종 실패 day=%s page=%d — skip", day, current_page)
    return None


def _extract_pairs(html: str, day: date) -> list[tuple[str, datetime]]:
    """검색 결과 HTML 에서 (rcept_no, datetime) 쌍 리스트 추출.

    행 안에서 HH:MM 패턴과 14자리 rcept_no 를 같이 찾는다. 표 구조나 CSS 클래스에
    의존하지 않아 페이지 구조 변경에 robust.
    """
    soup = BeautifulSoup(html, "lxml")
    out: list[tuple[str, datetime]] = []
    seen: set[str] = set()  # 같은 행이 여러 컬럼에 rcept_no 노출될 수 있어 dedup.

    for tr in soup.find_all("tr"):
        text = tr.get_text(" ", strip=True)
        if not text:
            continue
        time_m = TIME_RE.search(text)
        if not time_m:
            continue
        hh, mm = int(time_m.group(1)), int(time_m.group(2))
        if not (0 <= hh <= 23 and 0 <= mm <= 59):
            continue

        # rcept_no — 행 안 어딘가에서 14자리 숫자 찾기 (href, onclick, text 모두 포함).
        rcept_no: str | None = None
        for a in tr.find_all("a"):
            for attr_val in (a.get("href", ""), a.get("onclick", "")):
                m = RCEPT_NO_RE.search(attr_val)
                if m:
                    rcept_no = m.group(1)
                    break
            if rcept_no:
                break
        if not rcept_no:
            m = RCEPT_NO_RE.search(text)
            if m:
                rcept_no = m.group(1)
        if not rcept_no or rcept_no in seen:
            continue
        seen.add(rcept_no)

        try:
            dt = datetime(day.year, day.month, day.day, hh, mm, tzinfo=KST)
        except ValueError:
            continue
        out.append((rcept_no, dt))

    return out


def _flush_updates(coll, updates: list[UpdateOne]) -> int:
    if not updates:
        return 0
    res = _retry_mongo(
        lambda: coll.bulk_write(updates, ordered=False),
        "bulk_write",
    )
    return res.modified_count


def _load_resume_date(state_coll) -> date | None:
    doc = state_coll.find_one({"_id": STATE_ID})
    if not doc:
        return None
    last = doc.get("last_completed_date")
    if not last:
        return None
    return datetime.strptime(last, "%Y%m%d").date()


def _save_resume_date(state_coll, day: date) -> None:
    _retry_mongo(
        lambda: state_coll.update_one(
            {"_id": STATE_ID},
            {"$set": {"last_completed_date": _yyyymmdd(day),
                      "updated_at": datetime.now(KST)}},
            upsert=True,
        ),
        "save_resume_date",
    )


def run(start: date, end: date, *,
        dry_run: bool = False,
        sleep_sec: float = DEFAULT_SLEEP,
        max_results: int = DEFAULT_MAX_RESULTS,
        max_pages: int = DEFAULT_MAX_PAGES_PER_DAY,
        resume: bool = True) -> dict:
    client, disclosures, state = _connect_mongo()
    session = requests.Session()
    session.headers.update({"User-Agent": USER_AGENT})

    if resume:
        last = _load_resume_date(state)
        if last and last >= start:
            new_start = last + timedelta(days=1)
            if new_start > end:
                logger.info("이미 %s 까지 완료 — 할 일 없음", last)
                return {"days": 0, "pairs": 0, "modified": 0}
            logger.info("resume — %s 부터 재개 (마지막 완료: %s)", new_start, last)
            start = new_start

    total_days = (end - start).days + 1
    n_pairs = 0
    n_modified = 0
    n_http_fail = 0
    n_empty_days = 0
    started_at = time.monotonic()

    day = start
    days_done = 0
    while day <= end:
        page = 1
        day_pairs: list[tuple[str, datetime]] = []
        day_http_fail = 0

        while page <= max_pages:
            html = _fetch_page_html(session, day, page, max_results)
            time.sleep(sleep_sec)
            if html is None:
                day_http_fail += 1
                break

            pairs = _extract_pairs(html, day)
            if not pairs:
                # 빈 페이지 → 그 일자 종료 (페이지네이션 EOF).
                break
            day_pairs.extend(pairs)
            page += 1

        n_pairs += len(day_pairs)
        n_http_fail += day_http_fail
        if not day_pairs:
            n_empty_days += 1

        # dry-run 검증용: 첫 10개 페어 출력 + Mongo 매칭 카운트.
        if dry_run and day_pairs:
            sample = day_pairs[:10]
            logger.info("  샘플 (앞 %d개):", len(sample))
            for rcept_no, dt in sample:
                logger.info("    %s → %s", rcept_no, dt.strftime("%Y-%m-%d %H:%M %Z"))
            # Mongo 매칭 검증 — 실제로 update 됐을 건수만 카운트.
            ids = [rn for rn, _ in day_pairs]
            matched = disclosures.count_documents({"_id": {"$in": ids}})
            logger.info("  Mongo 매칭 %d/%d (rcept_no 가 disclosures 에 존재)",
                        matched, len(day_pairs))

        # Mongo bulk update.
        if not dry_run and day_pairs:
            updates = [
                UpdateOne(
                    {"_id": rcept_no},
                    {"$set": {"rcept_datetime": dt}},
                )
                for rcept_no, dt in day_pairs
            ]
            try:
                modified = _flush_updates(disclosures, updates)
                n_modified += modified
                _save_resume_date(state, day)
            except PyMongoError:
                logger.exception("day %s Mongo write 포기 — state 미갱신, 다음 resume 보강",
                                 day)

        days_done += 1
        elapsed = time.monotonic() - started_at
        rate = days_done / elapsed if elapsed else 0
        eta = (total_days - days_done) / rate if rate else 0
        logger.info(
            "%s: %d pages → %d pairs (mod=%d, http_fail=%d) "
            "— %d/%d days, ETA %.0fm",
            _yyyymmdd(day), page - 1, len(day_pairs),
            n_modified, day_http_fail,
            days_done, total_days, eta / 60,
        )

        day += timedelta(days=1)

    client.close()
    return {
        "days": days_done,
        "pairs": n_pairs,
        "modified": n_modified,
        "http_fail": n_http_fail,
        "empty_days": n_empty_days,
        "elapsed_sec": time.monotonic() - started_at,
    }


def main():
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )
    parser = argparse.ArgumentParser(
        description="dart.fss.or.kr 검색 페이지 → disclosures.rcept_datetime 보강 (v2)",
    )
    parser.add_argument("--start", default=DEFAULT_START_DATE)
    parser.add_argument("--end", default=DEFAULT_END_DATE)
    parser.add_argument("--dry-run", action="store_true",
                        help="Mongo write 없이 fetch + parse 만")
    parser.add_argument("--sleep", type=float, default=DEFAULT_SLEEP)
    parser.add_argument("--max-results", type=int, default=DEFAULT_MAX_RESULTS,
                        help="페이지당 표 행 수 (기본 100)")
    parser.add_argument("--max-pages", type=int, default=DEFAULT_MAX_PAGES_PER_DAY,
                        help="일자당 최대 페이지 (기본 100, safety cap)")
    parser.add_argument("--no-resume", dest="resume", action="store_false",
                        help="이전 진행 상태 무시")
    args = parser.parse_args()

    start = _parse_date(args.start)
    end = _parse_date(args.end)
    if start > end:
        logger.error("start(%s) > end(%s)", start, end)
        sys.exit(1)

    logger.info("시작: %s ~ %s (dry_run=%s, sleep=%s, max_results=%d, resume=%s)",
                start, end, args.dry_run, args.sleep, args.max_results, args.resume)

    stats = run(
        start, end,
        dry_run=args.dry_run,
        sleep_sec=args.sleep,
        max_results=args.max_results,
        max_pages=args.max_pages,
        resume=args.resume,
    )

    logger.info(
        "완료: %d days, pairs=%d, modified=%d, http_fail=%d, empty_days=%d / %.1fm",
        stats["days"], stats["pairs"], stats["modified"],
        stats["http_fail"], stats["empty_days"],
        stats["elapsed_sec"] / 60,
    )


if __name__ == "__main__":
    main()
