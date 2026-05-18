"""reclassify_disclosure_impact

MongoDB `modu_mongo.disclosures` 의 `impact_level` 필드를 현재
`collectors.disclosure_collector.classify_impact()` 기준으로 재계산.

언제 돌리나:
    POSITIVE_KEYWORDS / NEGATIVE_KEYWORDS 를 변경했을 때. 기존 백필 문서의
    impact_level 은 변경 시점의 키워드로 박혀 있으므로 재계산 필요.

idempotent:
    new == 기존 이면 update skip. 변경된 문서만 bulk_write.

사용법:
    python -m scripts.backfill.reclassify_disclosure_impact
    python -m scripts.backfill.reclassify_disclosure_impact --start 2023-01-01 --end 2024-12-31
    python -m scripts.backfill.reclassify_disclosure_impact --dry-run
"""

import argparse
import logging
import os
import sys
from pathlib import Path

from dotenv import load_dotenv
from pymongo import MongoClient, UpdateOne
from pymongo.errors import PyMongoError

load_dotenv(dotenv_path=Path(__file__).resolve().parents[2].parent / ".env")

from collectors.disclosure_collector import classify_impact  # noqa: E402

logger = logging.getLogger(__name__)

DB_NAME = "modu_mongo"
DISCLOSURE_COLL = "disclosures"
BULK_CHUNK = 2000
PROGRESS_EVERY = 50_000


def run(start: str | None, end: str | None, dry_run: bool) -> dict:
    coll = MongoClient(os.environ["MONGO_URI"])[DB_NAME][DISCLOSURE_COLL]

    query: dict = {}
    if start and end:
        query["rcept_dt"] = {"$gte": start, "$lte": end}
    elif start:
        query["rcept_dt"] = {"$gte": start}
    elif end:
        query["rcept_dt"] = {"$lte": end}

    total_estimate = coll.count_documents(query)
    logger.info("대상 추정 %d건 (query=%s)", total_estimate, query or "전체")

    cursor = coll.find(
        query,
        projection={"_id": 1, "report_nm": 1, "impact_level": 1},
        no_cursor_timeout=True,
    )

    n_seen = 0
    n_changed = 0
    transitions: dict = {}   # (old, new) → count
    pending: list[UpdateOne] = []

    try:
        for d in cursor:
            n_seen += 1
            old = d.get("impact_level") or "neutral"
            new = classify_impact(d.get("report_nm") or "")
            if new == old:
                continue
            n_changed += 1
            transitions[(old, new)] = transitions.get((old, new), 0) + 1
            if not dry_run:
                pending.append(UpdateOne(
                    {"_id": d["_id"]},
                    {"$set": {"impact_level": new}},
                ))
                if len(pending) >= BULK_CHUNK:
                    try:
                        coll.bulk_write(pending, ordered=False)
                    except PyMongoError as e:
                        logger.exception("bulk_write 실패 — chunk skip: %s", e)
                    pending = []

            if n_seen % PROGRESS_EVERY == 0:
                logger.info("진행 %d/%d (변경 %d)",
                            n_seen, total_estimate, n_changed)

        if pending and not dry_run:
            try:
                coll.bulk_write(pending, ordered=False)
            except PyMongoError as e:
                logger.exception("bulk_write 실패 — 잔여 chunk: %s", e)
    finally:
        cursor.close()

    return {
        "seen": n_seen,
        "changed": n_changed,
        "transitions": transitions,
    }


def main():
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )
    parser = argparse.ArgumentParser()
    parser.add_argument("--start", default=None,
                        help="시작일 YYYY-MM-DD (rcept_dt 비교용 YYYYMMDD 변환)")
    parser.add_argument("--end", default=None,
                        help="종료일 YYYY-MM-DD")
    parser.add_argument("--dry-run", action="store_true",
                        help="Mongo write 없이 분포만 확인")
    args = parser.parse_args()

    # rcept_dt 는 YYYYMMDD 문자열로 저장됨 → 변환.
    start = args.start.replace("-", "") if args.start else None
    end = args.end.replace("-", "") if args.end else None

    stats = run(start, end, dry_run=args.dry_run)

    logger.info("완료: 순회 %d건, 변경 %d건 (dry_run=%s)",
                stats["seen"], stats["changed"], args.dry_run)
    if stats["transitions"]:
        logger.info("변경 분포:")
        for (old, new), n in sorted(stats["transitions"].items(),
                                    key=lambda kv: -kv[1]):
            logger.info("  %s → %s  : %d", old, new, n)


if __name__ == "__main__":
    main()
