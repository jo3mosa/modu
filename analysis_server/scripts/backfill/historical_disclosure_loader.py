"""historical_disclosure_loader

OpenDART 과거 공시 백필 → MongoDB `modu_mongo.disclosures`.

백테스트 의사결정 로직 반복 수정을 위해 2023-01-01 ~ 현재 공시를 영구 보존한다.
라이브 disclosure_collector 는 Redis 단기 캐시(TTL 12h) 만 쓰므로 영구 데이터는 이 스크립트로 적재.

idempotent — `rcept_no` 를 `_id` 로 upsert. 중간 중단 후 재실행 안전.
진행 상태는 `modu_mongo.backfill_state` 컬렉션에 마지막 완료 일자 저장.

사용법:
    # repo 루트에서 실행 (DART_API_KEY, MONGO_URI 환경변수 필요)
    python -m scripts.backfill.historical_disclosure_loader
    python -m scripts.backfill.historical_disclosure_loader --start 2023-01-01 --end 2024-12-31
    python -m scripts.backfill.historical_disclosure_loader --start 2024-01-01 --end 2024-01-07 --dry-run
    python -m scripts.backfill.historical_disclosure_loader --no-resume   # state 무시하고 처음부터

quota:
    DART 일일 호출 한도 10K. 평일 평균 10-12 페이지/일 → 2년치 ~6K 호출 예상.
    quota 소진 시 KST 00:05 까지 대기 후 자동 재개.
"""

import argparse
import logging
import os
import sys
import time
from datetime import date, datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

from dotenv import load_dotenv
from pymongo import MongoClient, UpdateOne
from pymongo.errors import PyMongoError

# repo 루트의 .env 로드 (clients/* 와 동일 패턴)
load_dotenv(dotenv_path=Path(__file__).resolve().parents[2].parent / ".env")

from clients.dart_api_client import DartApiClient, DartCriticalError
from collectors.disclosure_collector import classify_impact, has_urgent  # noqa: E402

logger = logging.getLogger(__name__)

KST = ZoneInfo("Asia/Seoul")

# DART list.json 페이지당 항목 수 (최대치).
PAGE_COUNT = 100

# safety cap — 비정상 응답으로 무한 페이지네이션 방지.
MAX_PAGES_PER_DAY = 100

# API 호출간 sleep (초) — DART rate limit 회피. 명시적 초당 제한은 없으나 안전 마진.
DEFAULT_API_SLEEP = 0.3

# 기본 시작일 — 백테스트 학습 기간 시작.
DEFAULT_START_DATE = "2023-01-01"

# Mongo 컬렉션명.
DB_NAME = "modu_mongo"
DISCLOSURE_COLL = "disclosures"
STATE_COLL = "backfill_state"
STATE_ID = "disclosure_backfill"


def _parse_date(s: str) -> date:
    return datetime.strptime(s, "%Y-%m-%d").date()


def _yyyymmdd(d: date) -> str:
    return d.strftime("%Y%m%d")


def _rcept_dt_to_datetime(rcept_dt: str) -> datetime:
    """DART 의 YYYYMMDD 문자열 → KST 00:00:00 datetime. 인덱스 쿼리용."""
    return datetime.strptime(rcept_dt, "%Y%m%d").replace(tzinfo=KST)


def _connect_mongo():
    """MongoDB 연결. (db, disclosures_coll, state_coll) 반환. 실패 시 sys.exit(1).

    SSAFY 원격 서버의 idle connection drop 으로 백필이 끊긴 사례가 있어 timeout 을
    여유 있게 두고 retryWrites 활성화. transient 에러는 상위에서 _retry_mongo 가 추가
    재시도하지만, 첫 단계로 pymongo 자체 재연결 여지를 충분히 준다.
    """
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
    """Mongo write 를 backoff 와 함께 재시도. 모두 실패하면 마지막 예외 raise."""
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


def _ensure_indexes(coll) -> None:
    """as_of 쿼리(stock + rcept_date) 및 날짜 슬라이스용 인덱스."""
    coll.create_index([("stock_code", 1), ("rcept_date", -1)],
                      name="stock_rcept_date")
    coll.create_index([("rcept_date", -1)], name="rcept_date")


def _to_doc(raw: dict) -> dict:
    """DART list.json 원본 → Mongo 문서. _id = rcept_no."""
    rcept_dt = raw.get("rcept_dt", "")
    title = raw.get("report_nm", "")
    return {
        "_id": raw.get("rcept_no"),
        "stock_code": (raw.get("stock_code") or "").strip() or None,
        "corp_code": raw.get("corp_code"),
        "corp_name": raw.get("corp_name"),
        "corp_cls": raw.get("corp_cls"),     # Y/K/N/E (시장구분)
        "rcept_dt": rcept_dt,
        "rcept_date": _rcept_dt_to_datetime(rcept_dt) if rcept_dt else None,
        "report_nm": title,
        "flr_nm": raw.get("flr_nm"),
        "impact_level": classify_impact(title),   # 백테스트 쿼리 가속용 사전 계산
        "raw": raw,
    }


def _fetch_day(dart: DartApiClient, day: date, api_sleep: float) -> list[dict]:
    """하루치 전 페이지 합쳐 반환."""
    yyyymmdd = _yyyymmdd(day)
    all_items: list[dict] = []
    page_no = 1
    while page_no <= MAX_PAGES_PER_DAY:
        items, total_page = dart.get_all_disclosures(
            yyyymmdd, yyyymmdd, page_no=page_no, page_count=PAGE_COUNT,
        )
        if not items:
            break
        all_items.extend(items)
        if page_no >= total_page:
            break
        page_no += 1
        time.sleep(api_sleep)
    return all_items


def _upsert_bulk(coll, items: list[dict]) -> tuple[int, int]:
    """페이지 결과 일괄 upsert. (upserted_count, modified_count) 반환."""
    if not items:
        return 0, 0
    ops = [
        UpdateOne({"_id": doc["_id"]}, {"$set": doc}, upsert=True)
        for doc in (_to_doc(it) for it in items)
        if doc["_id"]   # rcept_no 없는 비정상 응답 방어
    ]
    if not ops:
        return 0, 0
    res = _retry_mongo(lambda: coll.bulk_write(ops, ordered=False), "bulk_write")
    return res.upserted_count, res.modified_count


def _load_resume_date(state_coll) -> date | None:
    """이전 실행에서 마지막으로 완료한 일자(다음 시작은 그 다음날)."""
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


def _wait_until_quota_reset() -> None:
    """DART quota(status=020) 소진 시 KST 익일 00:05 까지 대기."""
    now = datetime.now(KST)
    next_run = (now + timedelta(days=1)).replace(
        hour=0, minute=5, second=0, microsecond=0,
    )
    sleep_for = (next_run - now).total_seconds()
    logger.warning("DART quota 소진 — KST 00:05 까지 %.0f초 대기", sleep_for)
    time.sleep(sleep_for)


def run(start: date, end: date, *,
        resume: bool = True, dry_run: bool = False,
        api_sleep: float = DEFAULT_API_SLEEP) -> dict:
    """[start, end] 구간 백필. 통계 dict 반환."""
    dart = DartApiClient()
    client, disclosures, state = _connect_mongo()
    _ensure_indexes(disclosures)

    if resume:
        last = _load_resume_date(state)
        if last and last >= start:
            new_start = last + timedelta(days=1)
            if new_start > end:
                logger.info("이미 %s 까지 완료 — 할 일 없음", last)
                return {"days": 0, "fetched": 0, "upserted": 0, "modified": 0}
            logger.info("resume — %s 부터 재개 (마지막 완료: %s)", new_start, last)
            start = new_start

    total_days = (end - start).days + 1
    total_fetched = 0
    total_upserted = 0
    total_modified = 0
    started_at = time.monotonic()

    day = start
    days_done = 0
    while day <= end:
        try:
            items = _fetch_day(dart, day, api_sleep)
        except DartCriticalError as e:
            if e.status == "020":
                _wait_until_quota_reset()
                continue   # 같은 day 재시도
            logger.error("DART critical (status=%s) — 중단", e.status)
            break
        except Exception:
            logger.exception("fetch 실패 %s — skip", day)
            day += timedelta(days=1)
            continue

        total_fetched += len(items)
        up, mod = 0, 0
        if not dry_run:
            try:
                up, mod = _upsert_bulk(disclosures, items)
                _save_resume_date(state, day)
            except PyMongoError:
                # _retry_mongo 가 3회 재시도 후에도 실패한 transient 에러.
                # 해당 일자는 누락된 채로 두고 다음 일자로 진행.
                # state 가 갱신되지 않으므로 다음 resume 실행이 자동 보강.
                logger.exception("day %s Mongo write 포기 — 다음 일자로 계속", day)
                days_done += 1
                day += timedelta(days=1)
                continue
        total_upserted += up
        total_modified += mod

        days_done += 1
        elapsed = time.monotonic() - started_at
        eta = (elapsed / days_done) * (total_days - days_done) if days_done else 0
        logger.info(
            "%s: %d items (upsert +%d / modify %d) — %d/%d days, ETA %.0fm",
            _yyyymmdd(day), len(items), up, mod,
            days_done, total_days, eta / 60,
        )

        time.sleep(api_sleep)
        day += timedelta(days=1)

    client.close()
    return {
        "days": days_done,
        "fetched": total_fetched,
        "upserted": total_upserted,
        "modified": total_modified,
        "elapsed_sec": time.monotonic() - started_at,
    }


def main():
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )
    parser = argparse.ArgumentParser(
        description="OpenDART 과거 공시 백필 → MongoDB modu_mongo.disclosures",
    )
    parser.add_argument("--start", default=DEFAULT_START_DATE,
                        help=f"시작일 YYYY-MM-DD (기본 {DEFAULT_START_DATE})")
    parser.add_argument("--end", default=None,
                        help="종료일 YYYY-MM-DD (기본: 오늘 KST)")
    parser.add_argument("--no-resume", dest="resume", action="store_false",
                        help="이전 진행 상태 무시하고 처음부터")
    parser.add_argument("--dry-run", action="store_true",
                        help="Mongo write 없이 fetch 만 (페이지·건수 검증)")
    parser.add_argument("--sleep", type=float, default=DEFAULT_API_SLEEP,
                        help=f"API 호출간 sleep 초 (기본 {DEFAULT_API_SLEEP})")
    args = parser.parse_args()

    start = _parse_date(args.start)
    end = _parse_date(args.end) if args.end else datetime.now(KST).date()
    if start > end:
        logger.error("start(%s) > end(%s)", start, end)
        sys.exit(1)

    logger.info("백필 시작: %s ~ %s (resume=%s, dry_run=%s)",
                start, end, args.resume, args.dry_run)
    stats = run(start, end,
                resume=args.resume, dry_run=args.dry_run, api_sleep=args.sleep)
    logger.info(
        "백필 완료: %d days / fetched %d / upserted +%d / modified %d / %.1fm",
        stats["days"], stats["fetched"], stats["upserted"], stats["modified"],
        stats.get("elapsed_sec", 0) / 60,
    )


if __name__ == "__main__":
    main()
