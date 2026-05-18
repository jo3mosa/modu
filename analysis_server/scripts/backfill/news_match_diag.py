"""news_match_diag.py

Phase 1 매칭 결과 품질 평가용 인터랙티브 진단 도구.

두 모드:
  --mode precision : 매칭 적중 기사 N건 무작위 샘플 → 사람이 정답 여부 라벨링
  --mode recall    : 매칭 0개 기사 N건 무작위 샘플 → 본문에 실제 종목 언급이 있는데
                     놓친 건지 라벨링 + 누락 사유 분류

결과는 reports/news_match_diag_{mode}_{timestamp}.jsonl 에 저장.
누락 사유 분포는 다음 Phase (alias 보강 / 임베딩 도입) 우선순위 결정 근거.

라벨링 가이드 (일관성 위해):
  Precision (적중된 매칭이 옳은가?):
    - 매칭된 종목이 *기사의 주요 대상* 이면 OK
    - 단순 단어 일치만 있고 기사 본질과 무관하면 FP
    - 코드별로 OK/FP 가 갈리면 부분 정답으로 처리

  Recall (0개 매칭이 옳은가?):
    - 본문에 종목명·코드 직접 언급이 없는 시황·매크로 기사 → 0개가 정답
    - 종목 언급이 있는데 놓침 → FN (사유 분류)

사용:
    DB_HOST=... DB_PORT=... python -m scripts.backfill.news_match_diag --mode precision --n 50
    DB_HOST=... DB_PORT=... python -m scripts.backfill.news_match_diag --mode recall --n 50
"""

import argparse
import json
import os
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

from dotenv import load_dotenv
from pymongo import MongoClient


SCRIPT_DIR = Path(__file__).resolve().parent
ROOT_ENV = SCRIPT_DIR / ".." / ".." / ".." / ".env"
REPORTS_DIR = SCRIPT_DIR / "diag_reports"

DB_NAME = "modu_mongo"
COLLECTION_NAME = "news_articles"

# ANSI 색 (git bash 에서 OK)
HL = "\033[1;33m"   # 노란 굵게 — 매칭된 부분
DIM = "\033[2m"
RESET = "\033[0m"

# Precision FP 사유
P_REASONS = {
    "1": ("ambiguity", "모호한 그룹명(삼성·현대 등) 또는 짧은 이름이라 부적절"),
    "2": ("homonym", "동음이의 — 종목명이 일반 단어와 충돌"),
    "3": ("partial", "부분 일치 — 더 긴 단어의 일부만 종목명과 우연 매치"),
    "4": ("tangential", "본문에 등장하지만 기사 본질과 무관"),
    "5": ("other", "기타"),
}

# Recall FN 사유
R_REASONS = {
    "1": ("alias_missing", "줄임말/별칭 사전에 없음 (예: 새 약칭)"),
    "2": ("notation_variant", "표기 변형 (현대차 vs 현대자동차 등)"),
    "3": ("group_ambiguous", "그룹명만 등장 (삼성·현대 단독) — Phase 2 정책 대상"),
    "4": ("foreign_company", "외국 기업 — stock_master 에 없음 (정상)"),
    "5": ("indirect", "산업·테마만 언급, 종목 직접 언급은 없음 — 임베딩 매칭 후보"),
    "6": ("other", "기타"),
}


def _connect_mongo():
    load_dotenv(dotenv_path=str(ROOT_ENV))
    uri = os.getenv("MONGO_URI")
    if not uri:
        raise SystemExit("MONGO_URI 환경변수 없음")
    return MongoClient(uri, serverSelectionTimeoutMS=5000)


def _highlight(text, terms):
    """본문에서 terms 안의 문자열을 ANSI 하이라이트.

    terms 는 (display, code) 튜플 리스트. case-insensitive 매칭.
    하이라이트 충돌 방지 위해 길이 desc 정렬 + 1회만 치환.
    """
    if not text:
        return ""
    out = text
    seen_intervals = []
    for display, _code in sorted(set(terms), key=lambda x: -len(x[0])):
        if not display:
            continue
        lower = out.lower()
        needle = display.lower()
        start = 0
        while True:
            pos = lower.find(needle, start)
            if pos == -1:
                break
            end = pos + len(needle)
            if any(not (end <= s or pos >= e) for s, e in seen_intervals):
                start = pos + 1
                continue
            seen_intervals.append((pos, end))
            out = out[:pos] + HL + out[pos:end] + RESET + out[end:]
            # 색상 코드만큼 인덱스가 밀렸으므로 lower 다시 만들 필요
            lower = out.lower()
            start = end + len(HL) + len(RESET)
    return out


def _print_article(art, mode, idx, total):
    print()
    print("=" * 90)
    print(f"[{idx}/{total}]  _id={art.get('_id')}  date={art.get('date')}  source={art.get('source')}")
    print(f"제목: {art.get('title', '')}")
    print("-" * 90)

    title = art.get("title", "") or ""
    content = art.get("content") or art.get("summary") or ""
    # 너무 긴 본문은 앞 2000자 만
    content_display = content[:2000] + (f"\n{DIM}... (본문 {len(content)}자 중 2000자만 표시){RESET}"
                                        if len(content) > 2000 else "")

    if mode == "precision":
        meta = art.get("stock_match_meta", []) or []
        terms = []
        for m in meta:
            if m.get("name"):
                terms.append((m["name"], m["code"]))
            terms.append((m["code"], m["code"]))   # 코드 6자리 자체도 하이라이트
        print("매칭된 종목:")
        for m in meta:
            mk = ",".join(m.get("methods", []))
            star = " ★제목" if m.get("in_title") else ""
            print(f"  - {m['code']} ({m['name']})  hits={m['hits']}  methods={mk}{star}")
        print("-" * 90)
        print("제목:", _highlight(title, terms))
        print()
        print(_highlight(content_display, terms))
    else:  # recall
        print(f"{DIM}(매칭된 종목 없음 — 본문에 종목 언급이 있는지 확인){RESET}")
        print("-" * 90)
        print("제목:", title)
        print()
        print(content_display)
    print("=" * 90)


def _ask(prompt, valid_keys):
    while True:
        v = input(prompt).strip().lower()
        if v in valid_keys:
            return v
        print(f"  → {','.join(valid_keys)} 중 하나를 입력하세요.")


def _ask_reason(reason_map):
    print("  사유 선택:")
    for k, (name, desc) in reason_map.items():
        print(f"    {k}. {name} — {desc}")
    return _ask("  사유 번호: ", set(reason_map.keys()))


def run_precision(coll, n, jsonl_path):
    pipeline = [
        {"$match": {"stock_codes": {"$ne": []}, "matched_at": {"$exists": True}}},
        {"$sample": {"size": n}},
        {"$project": {"_id": 1, "date": 1, "source": 1, "title": 1, "content": 1,
                      "summary": 1, "stock_codes": 1, "stock_match_meta": 1}},
    ]
    samples = list(coll.aggregate(pipeline))
    print(f"\n[Precision 모드] {len(samples)}건 샘플링 — y(정답) / p(부분정답) / n(오답) / s(skip) / q(중단)\n")

    correct = partial = wrong = skipped = 0
    fp_reasons = Counter()
    with open(jsonl_path, "a", encoding="utf-8") as f:
        for i, art in enumerate(samples, 1):
            _print_article(art, "precision", i, len(samples))
            v = _ask("판정 [y/p/n/s/q]: ", {"y", "p", "n", "s", "q"})
            if v == "q":
                print("중단합니다.")
                break
            if v == "s":
                skipped += 1
                continue

            entry = {
                "mode": "precision",
                "_id": art["_id"],
                "verdict": {"y": "correct", "p": "partial", "n": "wrong"}[v],
                "stock_codes": art.get("stock_codes", []),
                "ts": datetime.now(timezone.utc).isoformat(),
            }
            if v == "y":
                correct += 1
            elif v == "p":
                partial += 1
                rk = _ask_reason(P_REASONS)
                entry["fp_reason"] = P_REASONS[rk][0]
                fp_reasons[P_REASONS[rk][0]] += 1
            else:
                wrong += 1
                rk = _ask_reason(P_REASONS)
                entry["fp_reason"] = P_REASONS[rk][0]
                fp_reasons[P_REASONS[rk][0]] += 1

            f.write(json.dumps(entry, ensure_ascii=False) + "\n")
            f.flush()

    total_labeled = correct + partial + wrong
    print("\n" + "=" * 60)
    print(f"Precision 집계 (라벨링 {total_labeled} / 샘플 {len(samples)} / skip {skipped})")
    if total_labeled:
        print(f"  정답:      {correct}  ({correct / total_labeled * 100:.1f}%)")
        print(f"  부분정답:  {partial}  ({partial / total_labeled * 100:.1f}%)")
        print(f"  오답:      {wrong}  ({wrong / total_labeled * 100:.1f}%)")
        print(f"  → strict precision (정답만):     {correct / total_labeled * 100:.1f}%")
        print(f"  → lenient precision (부분포함):  {(correct + partial) / total_labeled * 100:.1f}%")
    if fp_reasons:
        print("\n  FP 사유 분포:")
        for r, c in fp_reasons.most_common():
            print(f"    {r}: {c}")
    print(f"\n  저장: {jsonl_path}")


def run_recall(coll, n, jsonl_path):
    pipeline = [
        {"$match": {"stock_codes": {"$eq": []}, "matched_at": {"$exists": True}}},
        {"$sample": {"size": n}},
        {"$project": {"_id": 1, "date": 1, "source": 1, "title": 1, "content": 1,
                      "summary": 1}},
    ]
    samples = list(coll.aggregate(pipeline))
    print(f"\n[Recall 모드] {len(samples)}건 샘플링 — y(언급없음=정답) / n(언급있음=누락) / s(skip) / q(중단)\n")

    correct = missed = skipped = 0
    fn_reasons = Counter()
    with open(jsonl_path, "a", encoding="utf-8") as f:
        for i, art in enumerate(samples, 1):
            _print_article(art, "recall", i, len(samples))
            v = _ask("판정 [y/n/s/q]: ", {"y", "n", "s", "q"})
            if v == "q":
                print("중단합니다.")
                break
            if v == "s":
                skipped += 1
                continue

            entry = {
                "mode": "recall",
                "_id": art["_id"],
                "verdict": "no_mention" if v == "y" else "missed",
                "ts": datetime.now(timezone.utc).isoformat(),
            }
            if v == "y":
                correct += 1
            else:
                missed += 1
                rk = _ask_reason(R_REASONS)
                entry["fn_reason"] = R_REASONS[rk][0]
                fn_reasons[R_REASONS[rk][0]] += 1
                missing_codes = input("  실제로 매칭됐어야 할 종목코드(쉼표구분, 모르면 enter): ").strip()
                if missing_codes:
                    entry["expected_codes"] = [c.strip() for c in missing_codes.split(",") if c.strip()]

            f.write(json.dumps(entry, ensure_ascii=False) + "\n")
            f.flush()

    total_labeled = correct + missed
    print("\n" + "=" * 60)
    print(f"Recall 집계 (라벨링 {total_labeled} / 샘플 {len(samples)} / skip {skipped})")
    if total_labeled:
        print(f"  종목 언급 없음 (0개 매칭이 정답):  {correct}  ({correct / total_labeled * 100:.1f}%)")
        print(f"  종목 언급 있는데 누락:              {missed}  ({missed / total_labeled * 100:.1f}%)")
    if fn_reasons:
        print("\n  FN 사유 분포 (다음 Phase 우선순위 결정 근거):")
        for r, c in fn_reasons.most_common():
            tag = R_REASONS[next(k for k, v in R_REASONS.items() if v[0] == r)][1]
            print(f"    {r}: {c}  — {tag}")
    print(f"\n  저장: {jsonl_path}")


def parse_args():
    p = argparse.ArgumentParser(description="Phase 1 매칭 결과 precision/recall 평가")
    p.add_argument("--mode", choices=["precision", "recall"], required=True)
    p.add_argument("--n", type=int, default=50, help="샘플 수 (기본 50)")
    p.add_argument("--out", default=None, help="결과 JSONL 경로 (기본 reports/...)")
    return p.parse_args()


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    args = parse_args()

    REPORTS_DIR.mkdir(exist_ok=True)
    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    out_path = Path(args.out) if args.out else REPORTS_DIR / f"news_match_diag_{args.mode}_{ts}.jsonl"

    client = _connect_mongo()
    try:
        coll = client[DB_NAME][COLLECTION_NAME]
        if args.mode == "precision":
            run_precision(coll, args.n, out_path)
        else:
            run_recall(coll, args.n, out_path)
    finally:
        client.close()


if __name__ == "__main__":
    main()
