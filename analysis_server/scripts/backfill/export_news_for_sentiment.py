"""export_news_for_sentiment

MongoDB news_articles 의 매칭된 23-24년 뉴스를 JSONL 로 export.
Jupyter GPU 환경에서 FinBERT 일괄 처리하기 위한 단일 파일 추출.

대상:
    stock_codes 가 1개 이상 매칭된 뉴스 (백테스트에 실제 사용될 뉴스)
    + 23-24년 (가설 학습 기간)

출력 필드 (sentiment 계산 최소화):
    _id, title, content

사용법:
    python -m scripts.backfill.export_news_for_sentiment
    python -m scripts.backfill.export_news_for_sentiment --output ./news_export.jsonl
"""

import argparse
import json
import logging
import os
import sys
from pathlib import Path

from dotenv import load_dotenv
from pymongo import MongoClient

load_dotenv(dotenv_path=Path(__file__).resolve().parents[2].parent / ".env")

logger = logging.getLogger(__name__)

DB_NAME = "modu_mongo"
COLL_NAME = "news_articles"

DEFAULT_OUTPUT = Path(__file__).resolve().parent / "news_for_sentiment.jsonl"


def main():
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
        datefmt="%H:%M:%S",
    )
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default=str(DEFAULT_OUTPUT),
                        help=f"출력 JSONL 경로 (기본 {DEFAULT_OUTPUT})")
    parser.add_argument("--year", default=None,
                        help="특정 연도만 (YYYY). 기본은 23-24")
    args = parser.parse_args()

    coll = MongoClient(os.environ["MONGO_URI"])[DB_NAME][COLL_NAME]

    if args.year:
        date_filter = {"$regex": f"^{args.year}"}
    else:
        date_filter = {"$regex": "^(2023|2024)"}

    query = {
        "date": date_filter,
        "stock_codes": {"$exists": True, "$not": {"$size": 0}},
    }

    total = coll.count_documents(query)
    logger.info("대상 %d건 → %s", total, args.output)

    cursor = coll.find(
        query,
        projection={"_id": 1, "title": 1, "content": 1},
        no_cursor_timeout=True,
    )

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    n = 0
    try:
        with open(output_path, "w", encoding="utf-8") as f:
            for doc in cursor:
                f.write(json.dumps({
                    "_id": doc["_id"],
                    "title": doc.get("title", ""),
                    "content": doc.get("content", ""),
                }, ensure_ascii=False) + "\n")
                n += 1
                if n % 20_000 == 0:
                    logger.info("진행 %d/%d", n, total)
    finally:
        cursor.close()

    size_mb = output_path.stat().st_size / 1024 / 1024
    logger.info("완료: %d건 / %.1f MB → %s", n, size_mb, output_path)


if __name__ == "__main__":
    main()
