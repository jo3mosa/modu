"""sample_validation_csv

validate_news_window.py 에 던질 샘플 CSV 자동 생성.

전문가 picking 대신 MongoDB news_articles 에서 "활동량이 많았던" stock-day 쌍을
객관적으로 골라 다양한 rule_id 를 배정 — 검증 대표성 확보.

선택 로직:
    1. 최근 LOOKBACK_DAYS 일 내 published_at 기사 중 stock_codes 매핑된 것만
    2. (stock_code, 날짜) 별 기사 수 집계 → 상위 N 쌍 선택
    3. 각 쌍에 다양성 위해 RULE_ROTATION 순환으로 rule_id 배정
    4. timestamp 는 그 날 09:00 KST (장 시작 직전 — 트리거 가상 발화)

사용법:
    python -m scripts.backfill.sample_validation_csv --out samples.csv
    python -m scripts.backfill.sample_validation_csv --out samples.csv --n 30 --lookback 60
"""

from __future__ import annotations

import argparse
import csv
import logging
import os
import sys
from collections import Counter
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

from dotenv import load_dotenv

try:
    from pymongo import MongoClient
except ImportError:
    sys.stderr.write("pymongo 미설치 — pip install pymongo\n")
    sys.exit(2)

_REPO_ROOT = Path(__file__).resolve().parents[2]
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

logger = logging.getLogger(__name__)

KST = ZoneInfo("Asia/Seoul")


# 윈도우 다양성 확보용 rule_id 순환 — 시계열/단기 골고루.
# RULE_NEWS_WINDOWS 의 days/hours 분포 반영:
#   days 그룹: RSI/MFI(7d), MACD/BB(14d), REV/QUAL(14d)
#   hours 그룹: DART(24h), SENT(12h), PRICE/VOL/ATR(6~12h), EVT/TPL(24h)
RULE_ROTATION = [
    "RSI-001", "DART-001", "MACD-001", "SENT-001",
    "BB-001",  "PRICE-001", "MFI-001",  "VOL-001",
    "REV-001", "EVT-001",   "TPL-001",  "ATR-001",
]


def _connect_mongo():
    """MongoDB modu_mongo.news_articles 반환. 실패 시 종료."""
    load_dotenv()
    uri = os.getenv("MONGO_URI")
    if not uri:
        sys.stderr.write("MONGO_URI 환경변수 없음 — .env 확인\n")
        sys.exit(2)
    # SSAFY 원격 (k14b106.p.ssafy.io:30017) 응답 지연 대비 10초.
    client = MongoClient(uri, serverSelectionTimeoutMS=10000)
    client.admin.command("ping")
    return client["modu_mongo"]["news_articles"]


def pick_active_stock_days(lookback_days: int, top_n: int) -> list[tuple[str, str]]:
    """최근 N일 내 (stock_code, 날짜 YYYY-MM-DD) 별 기사 수 집계 → 상위 top_n 쌍 반환.

    aggregation pipeline 으로 한 번에 처리. ISO 문자열 published_at 의 앞 10자
    = 날짜.

    Returns: [(stock_code, date_str), ...] — top_n 개, 기사 많은 순.
    """
    coll = _connect_mongo()
    now_kst = datetime.now(KST)
    from_iso = (now_kst - timedelta(days=lookback_days)).isoformat()

    # buffer 는 충분히 크게 — top_n 의 ~10배 + 절대 하한 200.
    # 이전 top_n*3 은 활동 종목 수가 적을 때 (예: 30 요청 → 90건이 10종목에
    # 몰림 + 종목당 cap 2 → 20건만 픽업) 인위적 "데이터 부족" 오판을 유발.
    raw_limit = max(top_n * 10, 200)

    pipeline = [
        {"$match": {
            "stock_codes": {"$exists": True, "$ne": []},
            "published_at": {"$gte": from_iso},
        }},
        {"$unwind": "$stock_codes"},
        {"$project": {
            "stock_code": "$stock_codes",
            "date": {"$substr": ["$published_at", 0, 10]},   # YYYY-MM-DD
        }},
        {"$group": {
            "_id": {"stock": "$stock_code", "date": "$date"},
            "count": {"$sum": 1},
        }},
        {"$sort": {"count": -1}},
        {"$limit": raw_limit},
    ]

    raw = list(coll.aggregate(pipeline))
    logger.info("aggregation hit: %d 쌍 (raw_limit=%d, top_n=%d)",
                len(raw), raw_limit, top_n)

    # 종목 다양성 우선: cap=1 (종목당 1일) 부터 시작해 부족하면 점진적으로 완화.
    # 데이터가 충분한데 종목이 적은 케이스에서도 top_n 을 채울 수 있게 함.
    picked: list[tuple[str, str]] = []
    for cap in range(1, 11):  # 1~10일/종목까지 점진 완화
        seen_stocks: Counter = Counter()
        picked = []
        for r in raw:
            if len(picked) >= top_n:
                break
            stock = r["_id"]["stock"]
            date = r["_id"]["date"]
            if seen_stocks[stock] >= cap:
                continue
            picked.append((stock, date))
            seen_stocks[stock] += 1
        if len(picked) >= top_n:
            logger.info("픽업 완료: %d개 (종목당 최대 %d일)", len(picked), cap)
            break

    if len(picked) < top_n:
        logger.warning("요청 %d개에 못 미침 — %d개만 반환 (raw 풀 자체 부족: %d건)",
                       top_n, len(picked), len(raw))
    return picked


def assign_rule_id(idx: int) -> str:
    """샘플 인덱스 → rule_id (순환). 다양성 확보 목적."""
    return RULE_ROTATION[idx % len(RULE_ROTATION)]


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
        datefmt="%H:%M:%S",
    )
    p = argparse.ArgumentParser(description="validate_news_window.py 용 샘플 CSV 생성")
    p.add_argument("--out", required=True, type=Path,
                   help="출력 CSV 경로. 예: samples.csv")
    p.add_argument("--n", type=int, default=30,
                   help="생성할 샘플 수. 기본 30 (검증 통계 표본). 시간/토큰 줄이려면 줄여도 OK.")
    p.add_argument("--lookback", type=int, default=60,
                   help="MongoDB 조회 룩백 일수. 기본 60. 데이터 부족하면 늘릴 것.")
    p.add_argument("--hour", type=int, default=9, choices=range(0, 24),
                   metavar="{0-23}",
                   help="timestamp 시각 (KST, 0~23). 기본 9 (장 시작 직전).")
    args = p.parse_args()

    if args.n <= 0:
        sys.stderr.write(f"[ERROR] --n: 양의 정수 (received {args.n})\n")
        sys.exit(2)
    if args.lookback <= 0:
        sys.stderr.write(f"[ERROR] --lookback: 양의 정수 (received {args.lookback})\n")
        sys.exit(2)

    pairs = pick_active_stock_days(args.lookback, args.n)
    if not pairs:
        sys.stderr.write("샘플 0개 — MONGO_URI 와 news_articles 데이터 확인\n")
        sys.exit(2)

    with args.out.open("w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["stock_code", "timestamp", "rule_ids"])
        for i, (stock, date_str) in enumerate(pairs):
            # date_str = "YYYY-MM-DD", 시각은 args.hour, tz는 KST.
            ts = datetime.fromisoformat(date_str).replace(
                hour=args.hour, tzinfo=KST,
            )
            rule_id = assign_rule_id(i)
            w.writerow([stock, ts.isoformat(), rule_id])

    logger.info("✓ %d 샘플 저장: %s", len(pairs), args.out)
    logger.info("rule_id 분포: %s", Counter(assign_rule_id(i) for i in range(len(pairs))))


if __name__ == "__main__":
    main()
