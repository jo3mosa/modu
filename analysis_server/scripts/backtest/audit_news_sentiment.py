"""news_articles 컬렉션에 sentiment 점수와 종목 매핑이 채워져 있는지 점검.

사용법:
    python -m scripts.backtest.audit_news_sentiment
"""

import os
from pathlib import Path

from dotenv import load_dotenv
from pymongo import MongoClient

load_dotenv(dotenv_path=Path(__file__).resolve().parents[2].parent / ".env")


def main():
    client = MongoClient(os.environ["MONGO_URI"])
    coll = client.modu_mongo.news_articles
    stock_master = client.modu_mongo  # 별도 확인용

    yyyy_2324 = {"$regex": "^(2023|2024)"}

    print("=== 1. 카운트 ===")
    rows = [
        ("23-24 전체",
         {"date": yyyy_2324}),
        ("23-24 matched_at 있음 (매칭 시도됨)",
         {"date": yyyy_2324, "matched_at": {"$exists": True}}),
        ("23-24 stock_codes 1개 이상",
         {"date": yyyy_2324, "stock_codes": {"$exists": True, "$not": {"$size": 0}}}),
        ("23-24 stock_codes 빈배열 (매칭됐지만 결과 0)",
         {"date": yyyy_2324, "stock_codes": []}),
        ("23-24 stock_codes 필드 자체 누락",
         {"date": yyyy_2324, "stock_codes": {"$exists": False}}),
        ("23-24 sentiment_score 있음",
         {"date": yyyy_2324, "sentiment_score": {"$exists": True}}),
    ]
    width = max(len(label) for label, _ in rows)
    for label, q in rows:
        n = coll.count_documents(q)
        print(f"{label:<{width}}  {n:>10,}")

    print("\n=== 2. stock_codes 매칭 분포 (1개 이상 매칭된 기사 기준, 상위 10) ===")
    pipeline = [
        {"$match": {"date": yyyy_2324,
                    "stock_codes": {"$exists": True, "$not": {"$size": 0}}}},
        {"$project": {"n_codes": {"$size": "$stock_codes"}}},
        {"$group": {"_id": "$n_codes", "count": {"$sum": 1}}},
        {"$sort": {"_id": 1}},
        {"$limit": 10},
    ]
    for r in coll.aggregate(pipeline):
        print(f"  종목 {r['_id']}개 매칭: {r['count']:,}건")

    print("\n=== 3. PostgreSQL stock_master 사전 점검 ===")
    try:
        from clients.postgres_client import get_engine
        from sqlalchemy import text
        with get_engine().connect() as conn:
            n_master = conn.execute(text(
                "SELECT COUNT(*) FROM stock_master "
                "WHERE stock_name IS NOT NULL AND stock_name != ''"
            )).scalar()
            sample = conn.execute(text(
                "SELECT stock_code, stock_name FROM stock_master "
                "WHERE stock_name IS NOT NULL "
                "ORDER BY stock_code LIMIT 5"
            )).fetchall()
        print(f"  stock_master 종목명 보유 건수: {n_master:,}")
        print(f"  샘플 5건: {[(c, n) for c, n in sample]}")
    except Exception as e:
        print(f"  PostgreSQL 연결 실패 (DB_HOST=postgres 일 경우 DB_HOST=localhost 로 실행 필요): {e}")

    print("\n=== 4. 샘플 문서 (stock_codes 빈배열 케이스) ===")
    sample_empty = coll.find_one({
        "date": yyyy_2324,
        "stock_codes": [],
    })
    if sample_empty:
        title = sample_empty.get("title", "")[:80]
        print(f"  title: {title}")
        print(f"  date:  {sample_empty.get('date')}")
        print(f"  매칭 시도됐지만 종목 0개 — 종목 무관 일반 기사 또는 사전 누락")

    print("\n=== 5. 샘플 문서 (stock_codes 1개 이상 케이스) ===")
    sample_matched = coll.find_one({
        "date": yyyy_2324,
        "stock_codes": {"$exists": True, "$not": {"$size": 0}},
    })
    if sample_matched:
        title = sample_matched.get("title", "")[:80]
        print(f"  title: {title}")
        print(f"  stock_codes: {sample_matched.get('stock_codes')}")
        print(f"  매칭 meta: {sample_matched.get('stock_match_meta', [])[:3]}")


if __name__ == "__main__":
    main()
