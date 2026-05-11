"""news_stock_matcher.py

MongoDB 에 적재된 뉴스 기사 → 연관 종목 코드 매칭 일회성 백필.

매칭 전략 (1차 — 명시적 매칭만):
  (A) 종목코드 직접 언급
      - 본문/제목에서 6자리 숫자 추출 → stock_master.stock_code 와 교집합
      - 가장 정확하지만 기사에 코드 자체가 등장하는 경우는 소수
  (B) 종목명 등장
      - stock_master.stock_name 사전 → 본문/제목에서 substring 검색
      - 우선주(... 우, ... 우B, ...1우 등)는 본주로 흡수하지 않고 1차에서는 제외
      - longest-match-first: "LG디스플레이" 가 잡힌 구간을 mask 해서 "LG" 가 또 매칭되지 않게
      - 한글 종목명은 길이 ≥ 2 만 허용 (1자 종목명은 일반 단어와 충돌 위험)
      - 영문/영숫자 종목명은 word-boundary 검사 (양옆이 영숫자 아닐 때만)
  (C) Alias (사용자 정의)
      - news_stock_alias.json: {"별칭": "stock_code"} (예: {"삼전": "005930"})
      - 1자/2자 한글이라도 alias 에 등록되어 있으면 매칭됨

본 모듈에서 다루지 않는 것 (TODO 후속):
  - NER + 임베딩 기반 약한 매칭 (종목 직접 언급 없는 산업·테마 기사)
  - 섹터/업종 키워드 기반 broad 매칭
  - 본주-우선주 흡수 (우선주 뉴스도 본주에 매칭하고 싶으면 별도 매핑 필요)
  - 매칭 신뢰도 점수 (현재는 hit 횟수만 기록)

저장 스키마 (news_articles document 에 추가되는 필드):
  - stock_codes: [str]              — 매칭된 6자리 코드 (sparse index 활용)
  - stock_match_meta: [
        {
            "code": "005930",
            "name": "삼성전자",
            "hits": 3,                    — 총 발견 횟수
            "methods": ["name", "code"],  — 어떤 경로로 매칭됐는지
            "in_title": True              — 제목에 등장했는지 (가중치용)
        }, ...
    ]
  - matched_at: <UTC datetime>       — 멱등 키 (재처리 방지)

진입점:
    python news_stock_matcher.py                     # 미처리 기사만
    python news_stock_matcher.py --force             # 전체 재처리
    python news_stock_matcher.py --limit 100         # 디버깅용
"""

import argparse
import json
import os
import re
import sqlite3
import sys
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

from dotenv import load_dotenv
from pymongo import MongoClient, UpdateOne


# ---------- 경로/상수 ----------

# scripts/backfill/ → modu/ 루트로 3단계 상위 (.env), analysis_server/data (stock_master.db)
SCRIPT_DIR = Path(__file__).resolve().parent
ROOT_ENV = SCRIPT_DIR / ".." / ".." / ".." / ".env"
DEFAULT_DB_PATH = SCRIPT_DIR / ".." / ".." / "data" / "stock_master.db"
DEFAULT_ALIAS_PATH = SCRIPT_DIR / "news_stock_alias.json"

DB_NAME = "modu_mongo"
COLLECTION_NAME = "news_articles"

# 종목코드 직접 언급 패턴: 정확히 6자리 숫자 (앞뒤로 숫자 아닌 경계)
_CODE_PATTERN = re.compile(r"(?<!\d)(\d{6})(?!\d)")

# 우선주 식별: "...우", "...우B", "...1우", "...2우B" 등 — 본주에서 제외
_PREFERRED_SUFFIX = re.compile(r"(?:[12]?우B?)$")

# 종목명이 전부 영문/숫자/공백/하이픈으로만 구성되면 word-boundary 검사 대상
_ASCII_ONLY = re.compile(r"^[A-Za-z0-9\s\-&]+$")


# ---------- 사전 로딩 ----------

def load_stock_dict(db_path):
    """stock_master → {stock_code: stock_name}. 우선주/비활성 종목은 제외.

    한글 1자 종목명은 false-positive 위험이 너무 커 사전에서 제외하지만,
    종목코드 직접 매칭과 alias 매칭은 그대로 가능.
    """
    conn = sqlite3.connect(str(db_path))
    try:
        rows = conn.execute(
            "SELECT stock_code, stock_name FROM stock_master "
            "WHERE is_active = 1 AND stock_name IS NOT NULL AND stock_name != ''"
        ).fetchall()
    finally:
        conn.close()

    by_code = {}
    by_name_lower = {}
    for code, name in rows:
        code = str(code).zfill(6)
        name = name.strip()

        # 항상 코드 사전에는 등록 — 코드 직접 언급 매칭은 우선주도 인정해야
        # (예: 본문에 "066575" 가 그대로 적힌 경우)
        by_code[code] = name

        # 종목명 사전은 본주만, 길이 >= 2 만 허용
        if _PREFERRED_SUFFIX.search(name):
            continue
        if len(name) < 2:
            continue
        # 동명이종 방지: 같은 종목명이 두 코드에 매핑되면 ambiguous 로 보고 둘 다 제외
        key = name.lower()
        if key in by_name_lower:
            by_name_lower[key] = None   # sentinel
        else:
            by_name_lower[key] = code

    name_to_code = {n: c for n, c in by_name_lower.items() if c is not None}
    return by_code, name_to_code


def load_alias_dict(alias_path):
    """{"별칭": "stock_code"} JSON. 파일 없으면 빈 dict.

    `_` 로 시작하는 키(문서 주석용), value 가 숫자 코드 형식이 아닌 항목은 무시.
    """
    if not Path(alias_path).exists():
        return {}
    with open(alias_path, encoding="utf-8") as f:
        raw = json.load(f)
    out = {}
    for k, v in raw.items():
        k = (k or "").strip()
        if not k or k.startswith("_"):
            continue
        v_str = str(v).strip()
        if not v_str.isdigit() or len(v_str) > 6:
            continue
        out[k.lower()] = v_str.zfill(6)
    return out


# ---------- 매칭 알고리즘 ----------

def _is_word_boundary(text, start, end):
    """ASCII-only 패턴의 경계 검사 — 양옆이 영숫자가 아니어야 매칭."""
    left_ok = start == 0 or not text[start - 1].isalnum()
    right_ok = end >= len(text) or not text[end].isalnum()
    return left_ok and right_ok


def match_text(text, by_code, name_to_code, alias_to_code):
    """텍스트 1개에 대해 매칭 수행.

    반환: {stock_code: {"hits": int, "methods": set[str]}}
      methods: {"code", "name", "alias"} 중 일부
    """
    if not text:
        return {}

    results = defaultdict(lambda: {"hits": 0, "methods": set()})

    # ── (A) 종목코드 직접 언급 ────────────────────────────────────
    for m in _CODE_PATTERN.finditer(text):
        code = m.group(1)
        if code in by_code:
            results[code]["hits"] += 1
            results[code]["methods"].add("code")

    # ── (B) 종목명 / Alias 매칭 — longest-match-first + masking ──
    # 두 사전을 (term, code, method) 튜플로 통합 후 길이 desc 정렬.
    # 매칭된 구간은 placeholder 문자로 덮어써서 짧은 이름이 다시 잡히지 않게.
    text_lower = text.lower()
    masked = list(text_lower)

    combined = []
    for name, code in name_to_code.items():
        combined.append((name, code, "name"))
    for alias, code in alias_to_code.items():
        combined.append((alias, code, "alias"))
    combined.sort(key=lambda x: -len(x[0]))

    for term, code, method in combined:
        if not term:
            continue
        ascii_only = bool(_ASCII_ONLY.match(term))
        tlen = len(term)
        masked_str = "".join(masked)

        start = 0
        while True:
            pos = masked_str.find(term, start)
            if pos == -1:
                break
            end = pos + tlen
            # 영문/숫자 패턴은 word-boundary 필요
            if ascii_only and not _is_word_boundary(masked_str, pos, end):
                start = pos + 1
                continue

            results[code]["hits"] += 1
            results[code]["methods"].add(method)

            # 매칭 구간 mask (\x00 은 영숫자가 아니므로 word-boundary 도 깨지 않음)
            for i in range(pos, end):
                masked[i] = "\x00"
            masked_str = "".join(masked)
            start = end

    return dict(results)


def match_article(article, by_code, name_to_code, alias_to_code):
    """기사 1건 매칭. 제목/본문 별도 매칭 후 in_title 플래그 부여.

    반환:
      stock_codes: [code, ...]
      meta:        [{code, name, hits, methods, in_title}, ...]
    """
    title = article.get("title") or ""
    content = article.get("content") or article.get("summary") or ""

    title_hits = match_text(title, by_code, name_to_code, alias_to_code)
    body_hits = match_text(content, by_code, name_to_code, alias_to_code)

    # 두 결과 합치기
    merged = defaultdict(lambda: {"hits": 0, "methods": set(), "in_title": False})
    for code, info in title_hits.items():
        merged[code]["hits"] += info["hits"]
        merged[code]["methods"].update(info["methods"])
        merged[code]["in_title"] = True
    for code, info in body_hits.items():
        merged[code]["hits"] += info["hits"]
        merged[code]["methods"].update(info["methods"])

    # 정렬: 제목 등장 우선 → hits 내림차순 → code 오름차순
    sorted_codes = sorted(
        merged.items(),
        key=lambda kv: (not kv[1]["in_title"], -kv[1]["hits"], kv[0]),
    )

    stock_codes = [code for code, _ in sorted_codes]
    meta = [
        {
            "code": code,
            "name": by_code.get(code, ""),
            "hits": info["hits"],
            "methods": sorted(info["methods"]),
            "in_title": info["in_title"],
        }
        for code, info in sorted_codes
    ]
    return stock_codes, meta


# ---------- MongoDB 백필 진입점 ----------

def _connect_mongo():
    load_dotenv(dotenv_path=str(ROOT_ENV))
    uri = os.getenv("MONGO_URI")
    if not uri:
        raise ValueError("MONGO_URI 환경변수 없음 — .env 확인")
    client = MongoClient(uri, serverSelectionTimeoutMS=5000)
    client.admin.command("ping")
    return client


def run_matching(
    db_path=DEFAULT_DB_PATH,
    alias_path=DEFAULT_ALIAS_PATH,
    force=False,
    limit=None,
    batch_size=500,
):
    """MongoDB news_articles 전체 순회하며 매칭 결과 적재.

    Args:
        force: True 면 matched_at 있는 기사도 재처리
        limit: 디버깅용 처리 건수 상한
        batch_size: bulk_write 묶음 크기
    """
    by_code, name_to_code = load_stock_dict(db_path)
    alias_to_code = load_alias_dict(alias_path)
    print(f"[DICT] 종목코드 {len(by_code)} / 종목명 {len(name_to_code)} / alias {len(alias_to_code)}")

    client = _connect_mongo()
    coll = client[DB_NAME][COLLECTION_NAME]

    try:
        query = {} if force else {"matched_at": {"$exists": False}}
        total = coll.count_documents(query)
        if limit:
            total = min(total, limit)
        print(f"[START] 매칭 대상 {total}건 (force={force}, limit={limit})")

        cursor = coll.find(
            query,
            projection={"_id": 1, "title": 1, "content": 1, "summary": 1},
            no_cursor_timeout=True,
        )
        if limit:
            cursor = cursor.limit(limit)

        now = datetime.now(timezone.utc)
        ops = []
        processed = matched_any = 0
        hit_distribution = defaultdict(int)   # 매칭된 종목 수별 분포

        try:
            for art in cursor:
                stock_codes, meta = match_article(art, by_code, name_to_code, alias_to_code)
                hit_distribution[len(stock_codes)] += 1
                if stock_codes:
                    matched_any += 1

                ops.append(UpdateOne(
                    {"_id": art["_id"]},
                    {"$set": {
                        "stock_codes": stock_codes,
                        "stock_match_meta": meta,
                        "matched_at": now,
                    }},
                ))
                processed += 1

                if len(ops) >= batch_size:
                    coll.bulk_write(ops, ordered=False)
                    ops = []
                    print(f"  [{processed}/{total}] 진행 — 매칭 적중 {matched_any}건")

            if ops:
                coll.bulk_write(ops, ordered=False)
        finally:
            cursor.close()

        print(f"\n[FIN] 처리 {processed}건 / 매칭 적중 {matched_any}건 "
              f"({matched_any / processed * 100:.1f}%)" if processed else "[FIN] 처리 0건")
        print("[DIST] 매칭 종목 수별 분포 (상위 10):")
        for k in sorted(hit_distribution.keys())[:10]:
            print(f"   {k}개 종목 매칭: {hit_distribution[k]}건")

    finally:
        client.close()


# ---------- CLI ----------

def parse_args():
    p = argparse.ArgumentParser(description="MongoDB 뉴스 기사 → 종목 매칭 백필")
    p.add_argument("--force", action="store_true", help="이미 처리된 기사도 재처리")
    p.add_argument("--limit", type=int, default=None, help="처리 건수 상한 (디버깅)")
    p.add_argument("--batch", type=int, default=500, help="bulk_write 묶음 크기")
    p.add_argument("--db", default=str(DEFAULT_DB_PATH), help="stock_master.db 경로")
    p.add_argument("--alias", default=str(DEFAULT_ALIAS_PATH), help="alias JSON 경로")
    return p.parse_args()


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    args = parse_args()
    run_matching(
        db_path=args.db,
        alias_path=args.alias,
        force=args.force,
        limit=args.limit,
        batch_size=args.batch,
    )
