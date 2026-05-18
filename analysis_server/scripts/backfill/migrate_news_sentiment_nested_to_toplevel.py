"""migrate_news_sentiment_nested_to_toplevel

일회성 마이그레이션 — news_articles 의 옛 nested `sentiment` 필드를
백필 표준 top-level 필드 (`sentiment_score`, `sentiment_confidence`,
`sentiment_pos_prob`, `sentiment_neu_prob`, `sentiment_neg_prob`,
`sentiment_updated_at`) 로 옮긴다.

배경:
  - 라이브 news_collector 가 과거에 `{"sentiment": {sentiment_score: ...,
    confidence: ..., pos_prob: ...}}` 형식으로 적재.
  - 백필 스크립트(import_news_sentiment.py) 는 top-level 5+1 필드로 적재.
  - 백테스트 signal_generator 는 top-level `sentiment_score` 만 읽음.
  - schema 가 분기되어 라이브 적재분이 백테스트에서 안 보이는 문제.
  - 본 PR 에서 news_collector 를 top-level 로 통일했고, 본 스크립트가
    이미 적재된 nested 분을 같은 schema 로 옮긴다.

idempotent:
  - 이미 top-level 이 채워진 doc 은 건너뜀.
  - nested `sentiment` 필드는 변환 후 unset.

사용법:
    python -m scripts.backfill.migrate_news_sentiment_nested_to_toplevel
    python -m scripts.backfill.migrate_news_sentiment_nested_to_toplevel --dry-run
"""

import argparse
import logging
import os
from datetime import datetime, timezone
from pathlib import Path

from dotenv import load_dotenv
from pymongo import MongoClient, UpdateOne

load_dotenv(dotenv_path=Path(__file__).resolve().parents[2].parent / ".env")

logger = logging.getLogger(__name__)

DB_NAME = "modu_mongo"
COLL_NAME = "news_articles"


# nested key → top-level key 매핑.
# 라이브 analyzer 출력 (`confidence` 등) → 백필 표준 (`sentiment_confidence` 등).
FIELD_MAP = {
    "sentiment_score":   "sentiment_score",
    "confidence":        "sentiment_confidence",
    "pos_prob":          "sentiment_pos_prob",
    "neu_prob":          "sentiment_neu_prob",
    "neg_prob":          "sentiment_neg_prob",
}


def _build_ops(coll, dry_run: bool) -> list[UpdateOne]:
    """nested sentiment 가진 doc 만 찾아 변환 UpdateOne 리스트 생성."""
    # 변환 대상 = nested 있고 top-level sentiment_score 가 아직 없는 것.
    # top-level 이 이미 있으면 백필이 더 최신값일 수 있어 덮어쓰지 않는다.
    query = {
        "sentiment": {"$type": "object"},
        "sentiment_score": {"$exists": False},
    }
    cursor = coll.find(query, projection={"_id": 1, "sentiment": 1})

    ops: list[UpdateOne] = []
    now = datetime.now(timezone.utc)
    for doc in cursor:
        nested = doc.get("sentiment") or {}
        if not isinstance(nested, dict):
            continue
        new_fields = {}
        for src_k, dst_k in FIELD_MAP.items():
            v = nested.get(src_k)
            if v is not None:
                new_fields[dst_k] = v
        if not new_fields:
            continue
        new_fields["sentiment_updated_at"] = now
        ops.append(UpdateOne(
            {"_id": doc["_id"]},
            {"$set": new_fields, "$unset": {"sentiment": ""}},
        ))

    logger.info("변환 대상: %d 건 (dry_run=%s)", len(ops), dry_run)
    return ops


def main():
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
        datefmt="%H:%M:%S",
    )
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true",
                        help="실제 write 안 하고 대상 건수만 출력")
    args = parser.parse_args()

    uri = os.getenv("MONGO_URI")
    if not uri:
        raise RuntimeError("MONGO_URI 환경변수 없음 — .env 확인")
    coll = MongoClient(uri)[DB_NAME][COLL_NAME]

    ops = _build_ops(coll, args.dry_run)
    if not ops:
        logger.info("변환할 doc 없음 — 종료")
        return

    if args.dry_run:
        logger.info("dry-run: 실제 write 안 함. 첫 3건 _id 미리보기:")
        for op in ops[:3]:
            logger.info("  %s", op._filter.get("_id"))
        return

    result = coll.bulk_write(ops, ordered=False)
    logger.info(
        "완료 — matched=%d / modified=%d", result.matched_count, result.modified_count,
    )


if __name__ == "__main__":
    main()
