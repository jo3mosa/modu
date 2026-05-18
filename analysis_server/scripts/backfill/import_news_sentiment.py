"""import_news_sentiment

Jupyter GPU 환경에서 처리한 sentiment 결과 JSONL → MongoDB news_articles 업데이트.

입력 JSONL 한 줄 형식:
    {"_id": "...", "sentiment_score": -25.3, "confidence": 49.5,
     "neg_prob": 30.1, "neu_prob": 50.5, "pos_prob": 19.4}

저장 필드 (sentiment_analyzer.py 의 analyze() 출력과 동일 의미):
    sentiment_score      : -100 ~ +100  ((pos-neg) × (1-neu) × 100)
    sentiment_confidence : 0 ~ 100      ((1-neu) × 100)
    sentiment_neg_prob   : 0 ~ 100
    sentiment_neu_prob   : 0 ~ 100
    sentiment_pos_prob   : 0 ~ 100
    sentiment_updated_at : datetime (재처리 추적용)

idempotent: 기존 sentiment_score 가 있어도 덮어씀.

사용법:
    python -m scripts.backfill.import_news_sentiment
    python -m scripts.backfill.import_news_sentiment --input ./news_sentiment_results.jsonl
"""

import argparse
import json
import logging
import os
from datetime import datetime, timezone
from pathlib import Path

from dotenv import load_dotenv
from pymongo import MongoClient, UpdateOne
from pymongo.errors import PyMongoError

load_dotenv(dotenv_path=Path(__file__).resolve().parents[2].parent / ".env")

logger = logging.getLogger(__name__)

DB_NAME = "modu_mongo"
COLL_NAME = "news_articles"
BATCH = 1000

DEFAULT_INPUT = Path(__file__).resolve().parent / "news_sentiment_results.jsonl"


def main():
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
        datefmt="%H:%M:%S",
    )
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", default=str(DEFAULT_INPUT),
                        help=f"입력 JSONL 경로 (기본 {DEFAULT_INPUT})")
    args = parser.parse_args()

    input_path = Path(args.input)
    if not input_path.exists():
        logger.error("입력 파일 없음: %s", input_path)
        return

    coll = MongoClient(os.environ["MONGO_URI"])[DB_NAME][COLL_NAME]
    now = datetime.now(timezone.utc)

    ops: list[UpdateOne] = []
    n_total = 0
    n_skipped = 0

    with open(input_path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                rec = json.loads(line)
            except json.JSONDecodeError:
                n_skipped += 1
                continue

            doc_id = rec.get("_id")
            if not doc_id or "sentiment_score" not in rec:
                n_skipped += 1
                continue

            ops.append(UpdateOne(
                {"_id": doc_id},
                {"$set": {
                    "sentiment_score":      rec["sentiment_score"],
                    "sentiment_confidence": rec.get("confidence"),
                    "sentiment_neg_prob":   rec.get("neg_prob"),
                    "sentiment_neu_prob":   rec.get("neu_prob"),
                    "sentiment_pos_prob":   rec.get("pos_prob"),
                    "sentiment_updated_at": now,
                }},
            ))
            n_total += 1

            if len(ops) >= BATCH:
                try:
                    coll.bulk_write(ops, ordered=False)
                except PyMongoError as e:
                    logger.exception("bulk_write 실패 — chunk skip: %s", e)
                ops = []
                if n_total % 10_000 == 0:
                    logger.info("진행 %d건", n_total)

    if ops:
        try:
            coll.bulk_write(ops, ordered=False)
        except PyMongoError as e:
            logger.exception("bulk_write 실패 — 잔여 chunk: %s", e)

    logger.info("완료: 업데이트 %d건 / skip %d건", n_total, n_skipped)


if __name__ == "__main__":
    main()
