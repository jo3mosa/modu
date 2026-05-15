"""disclosure_collector

OpenDART `list.json` batch 호출 → 종목별 그룹화 → Redis `event:{stock_code}` 갱신.

3분 주기 폴링. 최근 2일치 공시를 한 번에 끌어와 종목별로 분배.
종목별 polling (2,768 호출/cycle) 대비 일일 API quota 수십 배 절약.
한 사이클 통상 1~10 페이지 (DART rate limit 10K/day 안에서 안전).

architecture spec:
    event:{stock_code} TTL 43200s (12시간), 3분 주기로 갱신
    값 = {"has_urgent_issue": bool, "recent_disclosures": [...]}
    영구 보존: MongoDB modu_mongo.disclosures (라이브 cycle 마다 idempotent upsert).
    23년~현재 백필은 scripts/backfill/historical_disclosure_loader.py 로 1회 실행.

사용법:
    python -m collectors.disclosure_collector              # 1 사이클 후 종료 (테스트)
    python -m collectors.disclosure_collector --loop       # 3분 무한 루프 (Docker PID 1)
    python -m collectors.disclosure_collector --loop --interval 300   # 5분 간격
"""

import argparse
import atexit
import logging
import os
import time
from collections import defaultdict
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

try:
    from pymongo import MongoClient, UpdateOne
    from pymongo.errors import PyMongoError
except ImportError:   # pymongo 미설치 환경 (테스트 등) 에서도 import 자체는 성공
    MongoClient = None
    UpdateOne = None
    PyMongoError = Exception

from clients.dart_api_client import DartApiClient, DartCriticalError
from clients.redis_client import set_json

logger = logging.getLogger(__name__)

# DART 공시 기준일은 한국시간. UTC 컨테이너에서 datetime.now() 그대로 쓰면
# KST 오전 시간대에 end_de 가 전일로 계산되어 당일 공시가 누락됨.
KST = ZoneInfo("Asia/Seoul")


# ─── 공시 임팩트 분류 ────────────────────────────────────────────────────────
# (룰베이스 시작용 — 추후 LLM 분류기로 대체 가능)

URGENT_KEYWORDS = ("조회공시", "거래정지", "관리종목", "감사의견거절", "소송", "회생절차")
POSITIVE_KEYWORDS = ("단일판매ㆍ공급계약체결", "자기주식취득", "무상증자", "현금ㆍ현물배당", "특허취득")
NEGATIVE_KEYWORDS = ("감사의견거절", "관리종목", "유상증자결정(제3자배정)", "전환사채권발행", "소송", "횡령")


def classify_impact(title: str) -> str:
    if any(kw in title for kw in NEGATIVE_KEYWORDS):
        return "negative"
    if any(kw in title for kw in POSITIVE_KEYWORDS):
        return "positive"
    return "neutral"


def has_urgent(disclosures: list[dict]) -> bool:
    return any(
        any(kw in (d.get("report_nm") or "") for kw in URGENT_KEYWORDS)
        for d in disclosures
    )


def format_disclosure(d: dict) -> dict:
    # OpenDART list.json은 접수 '시각' 미제공 → 접수일자(YYYYMMDD) 만 노출.
    # 정확한 HH:MM 이 필요하면 dart.fss.or.kr 상세 페이지 스크래핑 필요.
    return {
        "time": d.get("rcept_dt", ""),
        "title": d.get("report_nm", ""),
        "impact_level": classify_impact(d.get("report_nm", "")),
    }


def _to_mongo_doc(d: dict) -> dict:
    """DART list.json 원본 → Mongo 문서. _id = rcept_no.

    백필 스크립트(historical_disclosure_loader._to_doc) 와 동일 스키마 —
    백테스트가 백필분과 라이브 적재분을 구분 없이 같은 쿼리로 읽도록 한다.
    """
    rcept_dt = d.get("rcept_dt", "")
    title = d.get("report_nm", "")
    rcept_date = None
    if rcept_dt:
        try:
            rcept_date = datetime.strptime(rcept_dt, "%Y%m%d").replace(tzinfo=KST)
        except ValueError:
            pass   # 비정상 포맷은 rcept_date=None 으로 두고 그래도 적재
    return {
        "_id": d.get("rcept_no"),
        "stock_code": (d.get("stock_code") or "").strip() or None,
        "corp_code": d.get("corp_code"),
        "corp_name": d.get("corp_name"),
        "corp_cls": d.get("corp_cls"),
        "rcept_dt": rcept_dt,
        "rcept_date": rcept_date,
        "report_nm": title,
        "flr_nm": d.get("flr_nm"),
        "impact_level": classify_impact(title),
        "raw": d,
    }


def _get_mongo_collection():
    """lazy 연결. 실패 시 None 반환 — Mongo write 자체를 skip.

    pymongo 미설치·MONGO_URI 미설정·연결 실패 모두 silent skip 으로 처리.
    Redis 갱신이 라이브 의사결정 임계 경로이므로 Mongo 가 죽어도 사이클은 진행.
    """
    global _mongo_coll
    if _mongo_coll is not None:
        return _mongo_coll
    if MongoClient is None:
        logger.warning("pymongo 미설치 — Mongo 영속화 skip")
        return None
    uri = os.getenv("MONGO_URI")
    if not uri:
        logger.warning("MONGO_URI 미설정 — Mongo 영속화 skip")
        return None
    try:
        client = MongoClient(
            uri,
            serverSelectionTimeoutMS=10000,
            socketTimeoutMS=30000,
            connectTimeoutMS=20000,
            retryWrites=True,
        )
        client.admin.command("ping")
        coll = client[MONGO_DB][MONGO_COLL]
        # idempotent — 이미 있으면 무동작
        coll.create_index([("stock_code", 1), ("rcept_date", -1)],
                          name="stock_rcept_date")
        coll.create_index([("rcept_date", -1)], name="rcept_date")
        atexit.register(client.close)
        _mongo_coll = coll
        logger.info("✓ MongoDB 연결 — %s.%s", MONGO_DB, MONGO_COLL)
        return coll
    except Exception as e:
        logger.warning("MongoDB 연결 실패 — Mongo 영속화 skip: %s", e)
        return None


def persist_to_mongo(disclosures: list[dict]) -> int:
    """모든 fetched 공시(상장사+비상장) 를 idempotent upsert. 적재 건수 반환.

    실패 시 logger.exception 으로만 남기고 0 반환 — caller(run_once) 가
    Mongo 실패로 Redis write 를 막거나 사이클 stats 를 깨뜨리지 않게 한다.
    """
    coll = _get_mongo_collection()
    if coll is None or not disclosures:
        return 0
    ops = [
        UpdateOne({"_id": doc["_id"]}, {"$set": doc}, upsert=True)
        for doc in (_to_mongo_doc(d) for d in disclosures)
        if doc["_id"]   # rcept_no 없는 비정상 응답 방어
    ]
    if not ops:
        return 0
    try:
        res = coll.bulk_write(ops, ordered=False)
        return res.upserted_count + res.modified_count
    except PyMongoError:
        logger.exception("Mongo bulk_write 실패 — 다음 사이클 재시도")
        return 0


# ─── 설정 ────────────────────────────────────────────────────────────────────

# event 윈도우 — engine 의 DART-* 룰이 "최근" 으로 보는 기간.
# 2일이면 자정 직후 어제 늦은 공시도 안전하게 커버.
LOOKBACK_DAYS = 2

# 3분 주기 — architecture spec.
LOOP_INTERVAL_SEC = 180

# 한 종목당 보존할 최근 공시 갯수 (event 페이로드 크기 제한).
MAX_DISCLOSURES_PER_STOCK = 10

# Redis TTL — 12h. 위험 공시(거래정지·관리종목 등) 가 트리거에 충분히 오래
# 노출되도록 보장. 단순 사이클 margin (3분 폴링) 차원이면 10분으로 충분하지만,
# collector 가 죽거나 DART quota 소진(KST 자정 reset) 으로 갱신이 멈춰도
# 마지막 페이로드가 12 시간은 살아 있어야 engine 이 공시 신호를 잃지 않는다.
# LOOKBACK_DAYS=2 와 정합: 정상 운영 중에는 48h 윈도우 안의 공시가 매 3분
# 재발행되므로 사실상 48h 노출, TTL 12h 는 "collector 정지 시 최소 보장" 의미.
REDIS_TTL_SECONDS = 43200

# DART list.json 페이지당 항목 수 (DART 가 허용하는 max).
PAGE_COUNT = 100

# ─── MongoDB 영속화 ─────────────────────────────────────────────────────────
# Redis 는 12h hot cache (engine 1분 사이클이 읽는 곳), MongoDB 는 영구 보존
# (백테스트·재학습용). 둘은 독립 — Mongo write 실패해도 Redis write 와 cycle
# 진행은 영향받지 않게 격리한다.

MONGO_DB = "modu_mongo"
MONGO_COLL = "disclosures"

_mongo_coll = None   # lazy singleton — 첫 사이클에서 연결, 이후 재사용

# safety cap — 비정상 응답으로 무한 페이지네이션 방지.
MAX_PAGES = 100


# ─── 공시 수집 ──────────────────────────────────────────────────────────────

def fetch_recent_disclosures(
    dart: DartApiClient, days_back: int = LOOKBACK_DAYS,
) -> list[dict]:
    """DART batch list 모든 페이지 합쳐 반환."""
    now = datetime.now(KST)
    end_de = now.strftime("%Y%m%d")
    bgn_de = (now - timedelta(days=days_back)).strftime("%Y%m%d")

    disclosures: list[dict] = []
    page_no = 1
    pages_fetched = 0
    while page_no <= MAX_PAGES:
        batch, total_page = dart.get_all_disclosures(
            bgn_de, end_de, page_no=page_no, page_count=PAGE_COUNT,
        )
        pages_fetched += 1
        if not batch:
            break
        disclosures.extend(batch)
        if page_no >= total_page:
            break
        page_no += 1

    logger.info(
        "fetched %d disclosures across %d page(s) (%s ~ %s)",
        len(disclosures), pages_fetched, bgn_de, end_de,
    )
    return disclosures


def group_by_stock(disclosures: list[dict]) -> dict[str, list[dict]]:
    """공시 리스트 → stock_code 별 그룹. stock_code 가 비어 있으면 (비상장) 제외."""
    by_stock: dict[str, list[dict]] = defaultdict(list)
    for d in disclosures:
        sc = (d.get("stock_code") or "").strip()
        if sc:
            by_stock[sc].append(d)
    return dict(by_stock)


def build_event_payload(disclosures: list[dict]) -> dict:
    """종목별 공시 리스트 → event:{stock_code} 페이로드 (analysis_signals.event 와 동일)."""
    sorted_d = sorted(disclosures, key=lambda d: d.get("rcept_dt", ""), reverse=True)
    return {
        "has_urgent_issue": has_urgent(disclosures),
        "recent_disclosures": [
            format_disclosure(d) for d in sorted_d[:MAX_DISCLOSURES_PER_STOCK]
        ],
    }


# ─── 사이클 ─────────────────────────────────────────────────────────────────

def run_once(dart: DartApiClient) -> dict:
    """1 사이클 = batch fetch + 종목별 Redis SET. 통계 반환.

    DartCriticalError 는 caller (run_forever) 가 별도 처리하도록 propagate.
    여기서 0 stats 로 묻으면 quota 소진 후 3분마다 무한 재시도 패턴이 됨.
    """
    started = time.monotonic()
    try:
        disclosures = fetch_recent_disclosures(dart)
    except DartCriticalError:
        raise
    except Exception:
        logger.exception("fetch failed")
        return {
            "disclosures": 0, "stocks": 0, "wrote": 0,
            "elapsed_sec": time.monotonic() - started,
        }

    by_stock = group_by_stock(disclosures)
    wrote = 0
    for stock_code, items in by_stock.items():
        try:
            event = build_event_payload(items)
            set_json(f"event:{stock_code}", event, ttl_seconds=REDIS_TTL_SECONDS)
            wrote += 1
        except Exception:
            logger.exception("redis SET failed for %s", stock_code)

    # Redis 갱신(engine 임계 경로) 이후 Mongo 영속화 — 백테스트·재학습용.
    # persist_to_mongo 는 실패 시 내부에서 logger.exception + 0 반환하므로
    # 사이클 stats 와 흐름은 영향받지 않는다.
    persisted = persist_to_mongo(disclosures)

    elapsed = time.monotonic() - started
    logger.info(
        "cycle done: %d disclosures / %d listed stocks / %d wrote / "
        "%d persisted / %.1fs",
        len(disclosures), len(by_stock), wrote, persisted, elapsed,
    )
    return {
        "disclosures": len(disclosures),
        "stocks": len(by_stock),
        "wrote": wrote,
        "persisted": persisted,
        "elapsed_sec": elapsed,
    }


def run_forever(interval: int = LOOP_INTERVAL_SEC) -> None:
    """무한 루프 entrypoint (Docker PID 1).

    DartCriticalError 처리:
      - status=020 (quota 소진): KST 자정 +5분까지 대기 후 재개 (자정에 quota reset)
      - status=010/011/012 (인증·IP): 키 교체 전까지 복구 불가 — 루프 종료
        (docker restart policy 가 컨테이너 재기동 → 운영자가 .env 갱신 후 반영)
    """
    dart = DartApiClient()
    cycle = 0
    while True:
        cycle += 1
        logger.info("=== cycle %d start ===", cycle)
        started = time.monotonic()
        try:
            run_once(dart)
        except DartCriticalError as e:
            if e.status == "020":
                now = datetime.now(KST)
                next_run = (now + timedelta(days=1)).replace(
                    hour=0, minute=5, second=0, microsecond=0,
                )
                sleep_for = (next_run - now).total_seconds()
                logger.warning(
                    "DART quota 소진 (status=020) — 다음 KST 00:05 까지 %.0f초 대기",
                    sleep_for,
                )
                time.sleep(sleep_for)
                continue
            # 010/011/012 — 키 교체 필요
            logger.error(
                "DART critical (status=%s, message=%s) — 작업 중단",
                e.status, e.message,
            )
            return
        except Exception:
            logger.exception("cycle %d crashed", cycle)
        elapsed = time.monotonic() - started
        sleep_for = max(0, interval - elapsed)
        time.sleep(sleep_for)


# ─── CLI ────────────────────────────────────────────────────────────────────

def main():
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )
    parser = argparse.ArgumentParser(
        description="disclosure_collector — DART batch → Redis event:{stock}",
    )
    parser.add_argument("--loop", action="store_true",
                        help="무한 루프 (Docker PID 1 운영 모드). 기본은 1 사이클 후 종료")
    parser.add_argument("--interval", type=int, default=LOOP_INTERVAL_SEC,
                        help=f"--loop 사이클 간격 (초, 기본 {LOOP_INTERVAL_SEC})")
    args = parser.parse_args()

    if args.loop:
        run_forever(args.interval)
    else:
        run_once(DartApiClient())


if __name__ == "__main__":
    main()
