"""disclosure timestamp backfill 결과 점검.

23-24 년 stock_code 가 있는 공시 중 rcept_datetime 이 채워진 비율을 확인.

사용법:
    python -m scripts.backtest.audit_disclosure_coverage           # 카운트 요약
    python -m scripts.backtest.audit_disclosure_coverage --detail  # 누락 원인 진단
"""

import argparse
import os
from collections import Counter
from pathlib import Path

from dotenv import load_dotenv
from pymongo import MongoClient

load_dotenv(dotenv_path=Path(__file__).resolve().parents[2].parent / ".env")


YYYY_2324 = {"$regex": "^(2023|2024)"}
STOCK_IN = {"$ne": None}

MISSING_FILTER = {
    "stock_code": STOCK_IN,
    "rcept_dt": YYYY_2324,
    "rcept_datetime": {"$exists": False},
}


def _summary(coll):
    rows = [
        ("전체",
         {}),
        ("23-24 전체",
         {"rcept_dt": YYYY_2324}),
        ("23-24 stock_code 있음",
         {"stock_code": STOCK_IN, "rcept_dt": YYYY_2324}),
        ("23-24 stock_code 있음 + 시각 보강됨",
         {"stock_code": STOCK_IN, "rcept_dt": YYYY_2324,
          "rcept_datetime": {"$exists": True}}),
        ("23-24 stock_code 있음 + 시각 보강 안됨 (= 누락)",
         MISSING_FILTER),
    ]
    width = max(len(label) for label, _ in rows)
    for label, q in rows:
        n = coll.count_documents(q)
        print(f"{label:<{width}}  {n:>10,}")


def _detail(coll):
    print("\n=== 누락 분포 진단 ===")

    # 1. 시장 구분 (corp_cls)
    print("\n[1] 시장 구분 (corp_cls)")
    print("    Y=KOSPI, K=KOSDAQ, N=KONEX, E=기타")
    pipeline = [
        {"$match": MISSING_FILTER},
        {"$group": {"_id": "$corp_cls", "n": {"$sum": 1}}},
        {"$sort": {"n": -1}},
    ]
    for r in coll.aggregate(pipeline):
        print(f"    {r['_id'] or '(null)':<10} {r['n']:>10,}")

    # 2. 월별 분포 — 특정 시기 몰림 확인
    print("\n[2] 월별 누락 분포 (상위 12개)")
    pipeline = [
        {"$match": MISSING_FILTER},
        {"$group": {
            "_id": {"$substr": ["$rcept_dt", 0, 6]},  # YYYYMM
            "n": {"$sum": 1},
        }},
        {"$sort": {"n": -1}},
        {"$limit": 12},
    ]
    for r in coll.aggregate(pipeline):
        print(f"    {r['_id']:<10} {r['n']:>10,}")

    # 3. report_nm 단어 카운트 — 어떤 종류 공시가 많이 누락?
    print("\n[3] 누락된 report_nm 의 공통 키워드 (상위 15개)")
    # 보고서명에서 [정정], [기재정정] 같은 prefix 와 핵심 명사 추출.
    sample = coll.find(MISSING_FILTER, projection={"report_nm": 1}).limit(20000)
    word_counts: Counter = Counter()
    for d in sample:
        name = d.get("report_nm") or ""
        # [기재정정], (제3자배정) 같은 토큰 + 공백 분리 단어.
        for tok in name.replace("(", " (").replace(")", ") ").split():
            tok = tok.strip()
            if 2 <= len(tok) <= 25:
                word_counts[tok] += 1
    for tok, n in word_counts.most_common(15):
        print(f"    {tok:<25} {n:>10,}")

    # 4. 샘플 10건
    print("\n[4] 누락 샘플 10건 (rcept_no / rcept_dt / corp_cls / stock_code / report_nm)")
    samples = list(coll.find(
        MISSING_FILTER,
        projection={"_id": 1, "rcept_dt": 1, "corp_cls": 1,
                    "stock_code": 1, "report_nm": 1},
    ).limit(10))
    for d in samples:
        print(f"    {d['_id']}  {d.get('rcept_dt'):<8}  "
              f"{d.get('corp_cls') or '?':<2}  {d.get('stock_code'):<8}  "
              f"{(d.get('report_nm') or '')[:60]}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--detail", action="store_true",
                        help="누락 원인 진단 (시장/월/키워드/샘플)")
    args = parser.parse_args()

    coll = MongoClient(os.environ["MONGO_URI"]).modu_mongo.disclosures
    _summary(coll)
    if args.detail:
        _detail(coll)


if __name__ == "__main__":
    main()
