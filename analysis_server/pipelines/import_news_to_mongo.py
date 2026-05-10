"""import_news_to_mongo.py

크롤러가 이미 JSON으로 저장한 기사들을 MongoDB 로 일괄 import.

대상: news_data/*_YYYYMMDD.json (일자별 JSON 파일)
적재처: modu_mongo.news_articles
중복 처리: article_id 를 _id 로 매핑 → upsert 로 자연 dedup (여러 번 돌려도 중복 없음)

사용법:
    python import_news_to_mongo.py                  # 기본 ./news_data
    python import_news_to_mongo.py ./other_dir      # 다른 디렉터리 지정
"""

import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

from dotenv import load_dotenv
from pymongo import ASCENDING, DESCENDING, MongoClient, UpdateOne


DB_NAME = "modu_mongo"
COLLECTION_NAME = "news_articles"


def get_mongo_collection():
    """MongoDB 연결 + collection 반환. 모노레포 루트의 .env 자동 로드."""
    env_path = os.path.join(os.path.dirname(__file__), "../../.env")
    load_dotenv(dotenv_path=env_path)

    uri = os.getenv("MONGO_URI")
    if not uri:
        raise ValueError("MONGO_URI 환경변수 없음 — .env 확인")

    client = MongoClient(uri, serverSelectionTimeoutMS=5000)
    client.admin.command("ping")  # 연결 검증
    return client, client[DB_NAME][COLLECTION_NAME]


def ensure_indexes(collection):
    """필요 인덱스 생성 — 이미 있으면 noop."""
    collection.create_index(
        [("date", ASCENDING), ("source", ASCENDING)],
        name="date_source",
    )
    collection.create_index(
        [("published_at", DESCENDING)],
        name="published_desc",
    )
    collection.create_index(
        [("source", ASCENDING), ("category", ASCENDING)],
        name="source_category",
    )
    # 추후 NER 결과 채워질 stock_codes 필드용 (sparse — 비어있는 행 인덱스에서 제외)
    collection.create_index(
        [("stock_codes", ASCENDING), ("date", DESCENDING)],
        name="stock_date",
        sparse=True,
    )
    print("[OK] 인덱스 확인/생성 완료")


def import_json_file(collection, json_path):
    """단일 JSON 파일 → bulk upsert. (upserted, modified) 반환.

    구조가 list[dict] 가 아닌 파일(예: progress.json) 은 안전하게 skip.
    """
    with open(json_path, encoding="utf-8") as f:
        articles = json.load(f)

    # 일자별 article 파일은 list[dict] 구조여야 함 — 아니면 skip
    if not isinstance(articles, list):
        return 0, 0
    if not articles:
        return 0, 0

    now = datetime.now(timezone.utc)
    ops = []
    for art in articles:
        if not isinstance(art, dict):
            continue
        aid = art.get("article_id")
        if not aid:
            continue
        # _id 는 filter 로만 지정 — $set 에 _id 넣으면 immutable 에러 가능
        doc = {k: v for k, v in art.items() if k != "_id"}
        doc["imported_at"] = now
        ops.append(UpdateOne(
            {"_id": aid},
            {"$set": doc},
            upsert=True,
        ))

    if not ops:
        return 0, 0

    result = collection.bulk_write(ops, ordered=False)
    return result.upserted_count, result.modified_count


def import_directory(news_data_dir):
    """디렉터리 안의 모든 *_YYYYMMDD.json 파일을 import."""
    client, collection = get_mongo_collection()

    try:
        ensure_indexes(collection)

        # 일자별 JSON 만 매칭 — 8자리 숫자(YYYYMMDD)로 끝나는 파일만
        # (hankyung_progress.json 같은 비-기사 파일은 자동 제외)
        files = sorted(
            Path(news_data_dir).glob("*_[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9].json")
        )
        if not files:
            print(f"[WARN] {news_data_dir} 안에 일자별 JSON 파일이 없습니다.")
            return

        print(f"[START] {len(files)}개 파일 import — target: {DB_NAME}.{COLLECTION_NAME}")
        total_upserted = 0
        total_modified = 0

        for path in files:
            up, mod = import_json_file(collection, path)
            total_upserted += up
            total_modified += mod
            print(f"  {path.name}: upserted={up}, modified={mod}")

        total_in_collection = collection.count_documents({})
        print(f"\n[FIN] 신규 적재 {total_upserted}건 / 갱신 {total_modified}건 / "
              f"컬렉션 총 {total_in_collection}건")
    finally:
        client.close()


if __name__ == "__main__":
    news_dir = sys.argv[1] if len(sys.argv) > 1 else "./news_data"
    import_directory(news_dir)
