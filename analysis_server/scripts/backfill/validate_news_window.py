"""validate_news_window

Step 1 — "결정 안정성(decision stability)" 으로 뉴스 요약 윈도우 n 검증.

가설: 어떤 n 이상에서는 뉴스 요약을 더 길게 줘도 ai_agent 결정이 바뀌지 않는다.
     → 결정 일치율이 임계값(기본 90%) 이상이 되는 가장 작은 n 채택.

검증 방식 (정확도 아닌 안정성!):
    1. 과거 트리거 발화 샘플 CSV 로드 (stock_code, timestamp, rule_ids)
    2. 각 샘플에 대해 n ∈ {3, 7, 14} 일 윈도우로 요약 3종 생성
    3. 각 요약을 ai_agent.run_pipeline 에 동일 prompt 로 던지고 BUY/SELL/HOLD 결정 수집
    4. n=7 vs n=14, n=3 vs n=7 결정 일치율 집계
    5. 권장 n 산출

사전 준비 (한 번만):
    cd analysis_server
    .venv/Scripts/pip install -r ../ai_agent/requirements.txt
    # ai_agent 의 langgraph + langchain stack 이 필요.

사용법:
    python -m scripts.backfill.validate_news_window --csv samples.csv --user-id 1
    python -m scripts.backfill.validate_news_window --csv samples.csv --user-id 1 --windows 3,7,14
    python -m scripts.backfill.validate_news_window --csv samples.csv --user-id 1 --repeat 3
    python -m scripts.backfill.validate_news_window --csv samples.csv --user-id 1 --mode B

CSV schema (sample_validation_csv.py 가 자동 생성):
    stock_code,timestamp,rule_ids
    005930,2026-05-15T09:00:00+09:00,RSI-001;BB-001
    000660,2026-05-16T09:00:00+09:00,DART-001
"""

from __future__ import annotations

import argparse
import csv
import json
import logging
import sys
from collections import Counter
from datetime import datetime
from pathlib import Path
from typing import Optional
from uuid import uuid4

# ─── sys.path 설정 ──────────────────────────────────────────────────────────
# analysis_server 모듈 (engine.*, clients.*) 과 ai_agent 모듈 (app.*) 모두 import.
# analysis_server 모듈은 _REPO_ROOT, ai_agent 모듈은 _AI_AGENT_ROOT 기준.

_REPO_ROOT = Path(__file__).resolve().parents[2]    # .../analysis_server/
_MODU_ROOT = Path(__file__).resolve().parents[3]    # .../modu/
_AI_AGENT_ROOT = _MODU_ROOT / "ai_agent"

for p in (_REPO_ROOT, _AI_AGENT_ROOT):
    sp = str(p)
    if sp not in sys.path:
        sys.path.insert(0, sp)

# ── analysis_server 모듈 import (항상 가능)
from clients import llm_client                              # noqa: E402
from engine.detection_engine import RULE_REASONS            # noqa: E402
from engine.event_publisher import (                        # noqa: E402
    _SUMMARY_SYSTEM_PROMPT,
    _build_summary_prompt,
    _fetch_news_for_summary,
)

# ── ai_agent 모듈 import (deps 누락 시 그래도 import 단계는 통과)
_AI_AGENT_IMPORT_ERROR: Optional[str] = None
try:
    from app.graph.runner import run_pipeline               # noqa: E402
    from app.triggers.schemas import MarketTrigger, UserTriggerEvent   # noqa: E402
except ImportError as e:
    run_pipeline = None
    MarketTrigger = None
    UserTriggerEvent = None
    _AI_AGENT_IMPORT_ERROR = str(e)


logger = logging.getLogger(__name__)


DEFAULT_WINDOWS_DAYS = (3, 7, 14)
DEFAULT_AGREEMENT_THRESHOLD = 0.90    # 일치율 ≥ 90% → 더 작은 n 으로 충분
DEFAULT_REPEAT = 1                    # agent 비결정성 대응. 클수록 안정성 ↑ 비용 ↑
DEFAULT_USER_ID = 1                   # dummy. memory/portfolio 없는 user 라도 graph 동작.
DEFAULT_MODE = "A"                    # A: Bull/Bear 토론. B: 단일 에이전트.


# ─── CSV 로드 ───────────────────────────────────────────────────────────────

def load_samples(csv_path: Path) -> list[dict]:
    """CSV → 샘플 dict 리스트. rule_ids 는 세미콜론 구분."""
    samples = []
    with csv_path.open(encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            stock = (row.get("stock_code") or "").strip()
            ts_raw = (row.get("timestamp") or "").strip()
            rules_raw = (row.get("rule_ids") or "").strip()
            if not stock or not ts_raw:
                continue
            try:
                ts = datetime.fromisoformat(ts_raw)
            except ValueError:
                logger.warning("timestamp 파싱 실패 (skip): %s", ts_raw)
                continue
            rule_ids = [r.strip() for r in rules_raw.split(";") if r.strip()]
            samples.append({
                "stock_code": stock,
                "timestamp":  ts,
                "rule_ids":   rule_ids,
            })
    return samples


# ─── 요약 생성 ──────────────────────────────────────────────────────────────

def summarize_for_window(stock_code: str, anchor_dt: datetime, days: int) -> tuple[Optional[str], int]:
    """주어진 n일 윈도우로 요약 생성. (summary, article_count) 반환.

    기사 0건이면 (None, 0). LLM 실패면 (None, count).
    """
    window = ("days", days)
    articles = _fetch_news_for_summary(stock_code, anchor_dt, window)
    if not articles:
        return None, 0
    prompt = _build_summary_prompt(stock_code, window, articles)
    summary = llm_client.summarize(system=_SUMMARY_SYSTEM_PROMPT, user=prompt)
    return summary, len(articles)


# ─── agent 호출 ─────────────────────────────────────────────────────────────

def _build_user_trigger_event(
    *,
    stock_code: str,
    rule_ids: list[str],
    anchor_dt: datetime,
    user_id: int,
    news_summary_block: Optional[dict],
) -> "UserTriggerEvent":
    """검증용 UserTriggerEvent 구성.

    analysis_snapshot 에 (a) 빈 signals 4종 (b) news_summary 만 주입.
    portfolio_snapshot/user_context 는 빈 dict — context_loader 가 DB 에서 채움.

    ai_agent.backtest.adapters.graph_decision._to_user_trigger_event 패턴 차용.
    """
    analysis_snapshot: dict = {
        "stock_code": stock_code,
        "timestamp":  anchor_dt.isoformat(),
        "signals": {
            "technical":   {},
            "fundamental": {},
            "event":       {},
            "sentiment":   {},
        },
    }
    if news_summary_block is not None:
        analysis_snapshot["news_summary"] = news_summary_block

    return UserTriggerEvent(
        event_id=f"validate_{stock_code}_{uuid4().hex[:6]}",
        source_event_id=None,
        timestamp=anchor_dt,
        as_of=anchor_dt,
        user_id=user_id,
        stock_code=stock_code,
        trigger=MarketTrigger(
            rule_ids=list(rule_ids),
            trigger_reason=[RULE_REASONS.get(rid, rid) for rid in rule_ids],
        ),
        analysis_snapshot=analysis_snapshot,
        portfolio_snapshot={},
        user_context={},
    )


def _extract_decision(final_state) -> str:
    """run_pipeline 반환 state → "BUY"|"SELL"|"HOLD"|"UNKNOWN" 매핑.

    ai_agent.backtest.adapters.graph_decision._to_da_decision 패턴 차용.
    FinalDecision.action == "trade" 면 side(buy/sell), "hold" 면 HOLD.
    """
    fd = final_state.get("final_decision") if isinstance(final_state, dict) else getattr(final_state, "final_decision", None)
    if fd is None:
        return "UNKNOWN"
    dump = fd.model_dump() if hasattr(fd, "model_dump") else dict(fd)
    action = dump.get("action")
    side = dump.get("side")
    if action == "hold" or side is None:
        return "HOLD"
    if side in ("buy", "sell"):
        return side.upper()
    return "UNKNOWN"


def _call_agent(
    *,
    stock_code: str,
    rule_ids: list[str],
    anchor_dt: datetime,
    news_summary_block: Optional[dict],
    user_id: int,
    mode: str,
) -> str:
    """ai_agent.run_pipeline 호출 → 결정 추출. 예외 시 UNKNOWN.

    in-process 호출 — Kafka/HTTP 우회. ai_agent.backtest.adapters.graph_decision
    가 같은 패턴 (DA backtest 어댑터) 으로 production graph 를 invoke 하며 검증됨.
    """
    if run_pipeline is None:
        # import 단계에서 실패 — main 진입 시 차단되므로 여기 안 옴.
        return "UNKNOWN"

    event = _build_user_trigger_event(
        stock_code=stock_code,
        rule_ids=rule_ids,
        anchor_dt=anchor_dt,
        user_id=user_id,
        news_summary_block=news_summary_block,
    )
    try:
        final_state = run_pipeline(event, mode=mode)
    except Exception:
        logger.exception("run_pipeline 실패 (stock=%s)", stock_code)
        return "UNKNOWN"
    return _extract_decision(final_state)


# ─── 검증 코어 ──────────────────────────────────────────────────────────────

def majority_vote(decisions: list[str]) -> str:
    """repeat 호출 결과의 다수결. 동률은 첫 등장 순. 비면 'UNKNOWN'."""
    if not decisions:
        return "UNKNOWN"
    return Counter(decisions).most_common(1)[0][0]


def evaluate_sample(
    sample: dict, windows: tuple[int, ...], repeat: int,
    user_id: int, mode: str,
) -> dict:
    """단일 샘플에 대해 windows 별 결정 수집.

    각 윈도우마다 (a) 뉴스 요약 생성 (b) agent 호출 repeat 회 (c) 다수결.

    Returns:
        {
          "stock_code", "timestamp", "rule_ids",
          "by_window": {n: {"decision": str, "article_count": int}},
          "summaries_missing": [n, ...]   # 기사 0건 윈도우
        }
    """
    by_window: dict[int, dict] = {}
    missing: list[int] = []
    for n in windows:
        summary, article_count = summarize_for_window(sample["stock_code"], sample["timestamp"], n)
        if article_count == 0:
            missing.append(n)
            by_window[n] = {"decision": "UNKNOWN", "article_count": 0}
            continue
        news_block = {
            "summary":       summary,
            "window":        {"kind": "days", "value": n},
            "article_count": article_count,
            "top_articles":  [],   # 검증엔 메타 불필요 — 요약 자체가 핵심 변인.
        }
        decisions = [
            _call_agent(
                stock_code=sample["stock_code"],
                rule_ids=sample["rule_ids"],
                anchor_dt=sample["timestamp"],
                news_summary_block=news_block,
                user_id=user_id,
                mode=mode,
            )
            for _ in range(repeat)
        ]
        by_window[n] = {
            "decision":      majority_vote(decisions),
            "article_count": article_count,
            "decisions_raw": decisions,
        }
    return {
        "stock_code":        sample["stock_code"],
        "timestamp":         sample["timestamp"].isoformat(),
        "rule_ids":          sample["rule_ids"],
        "by_window":         by_window,
        "summaries_missing": missing,
    }


def agreement_rate(results: list[dict], a: int, b: int) -> tuple[float, int]:
    """결과 리스트에서 windows a, b 의 결정 일치율 + 비교 가능 샘플 수.

    'UNKNOWN' 인 샘플은 분모에서 제외 — 진짜 비교 가능한 것만 카운트.
    """
    total = 0
    agreed = 0
    for r in results:
        da = r["by_window"].get(a, {}).get("decision")
        db = r["by_window"].get(b, {}).get("decision")
        if not da or not db or da == "UNKNOWN" or db == "UNKNOWN":
            continue
        total += 1
        if da == db:
            agreed += 1
    return (agreed / total if total else 0.0, total)


def decision_distribution(results: list[dict], window: int) -> dict[str, int]:
    """주어진 윈도우에서 BUY/SELL/HOLD/UNKNOWN 분포."""
    c = Counter()
    for r in results:
        d = r["by_window"].get(window, {}).get("decision", "UNKNOWN")
        c[d] += 1
    return dict(c)


def recommend_window(results: list[dict], windows: tuple[int, ...],
                     threshold: float) -> int:
    """가장 작은 n 부터 보며, 인접 큰 n 과의 일치율 ≥ threshold 이면 n 채택.

    모두 threshold 미달이면 최대 windows 반환 (보수적 — 더 많은 컨텍스트).
    """
    sorted_w = sorted(windows)
    for i in range(len(sorted_w) - 1):
        a, b = sorted_w[i], sorted_w[i + 1]
        rate, total = agreement_rate(results, a, b)
        if rate >= threshold:
            return a
    return sorted_w[-1]


# ─── 리포팅 ─────────────────────────────────────────────────────────────────

def print_report(results: list[dict], windows: tuple[int, ...], threshold: float,
                 out_json: Optional[Path]) -> None:
    """콘솔 + (옵션) JSON 파일에 리포트 출력."""
    sorted_w = sorted(windows)

    print("\n" + "=" * 70)
    print(f"  뉴스 요약 윈도우 검증 리포트  (샘플 {len(results)}건)")
    print("=" * 70)

    # 1) 결정 분포
    print("\n[결정 분포]")
    print(f"  {'window':<10} {'BUY':>5} {'SELL':>5} {'HOLD':>5} {'UNK':>5}")
    for n in sorted_w:
        dist = decision_distribution(results, n)
        print(f"  n={n:<8} "
              f"{dist.get('BUY', 0):>5} {dist.get('SELL', 0):>5} "
              f"{dist.get('HOLD', 0):>5} {dist.get('UNKNOWN', 0):>5}")

    # 2) 일치율 매트릭스
    print("\n[일치율 (인접 윈도우)]")
    for i in range(len(sorted_w) - 1):
        a, b = sorted_w[i], sorted_w[i + 1]
        rate, total = agreement_rate(results, a, b)
        marker = "✓" if rate >= threshold else "✗"
        print(f"  n={a} vs n={b}:  {rate*100:5.1f}%  (비교 가능 {total}건)  {marker}")

    # 3) 권장값
    rec = recommend_window(results, windows, threshold)
    print(f"\n[권장 n] {rec}일  (일치율 임계값 {threshold*100:.0f}%)")
    print("=" * 70 + "\n")

    if out_json:
        with out_json.open("w", encoding="utf-8") as f:
            json.dump({
                "windows":             list(sorted_w),
                "threshold":           threshold,
                "recommended":         rec,
                "decision_distribution": {
                    n: decision_distribution(results, n) for n in sorted_w
                },
                "agreement_rates": [
                    {
                        "a":     sorted_w[i],
                        "b":     sorted_w[i + 1],
                        "rate":  agreement_rate(results, sorted_w[i], sorted_w[i + 1])[0],
                        "total": agreement_rate(results, sorted_w[i], sorted_w[i + 1])[1],
                    }
                    for i in range(len(sorted_w) - 1)
                ],
                "per_sample": results,
            }, f, ensure_ascii=False, indent=2, default=str)
        logger.info("상세 결과 JSON 저장: %s", out_json)


# ─── CLI ────────────────────────────────────────────────────────────────────

def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
        datefmt="%H:%M:%S",
    )
    parser = argparse.ArgumentParser(description="뉴스 요약 윈도우 n 결정안정성 검증")
    parser.add_argument("--csv", required=True, type=Path,
                        help="샘플 CSV 경로 (columns: stock_code,timestamp,rule_ids)")
    parser.add_argument("--user-id", type=int, default=DEFAULT_USER_ID,
                        help=f"agent 호출 시 사용할 dummy user_id. 기본 {DEFAULT_USER_ID}. "
                             "memory/portfolio 없어도 graph 동작하나, DB 에 존재하는 ID 가 안전.")
    parser.add_argument("--mode", default=DEFAULT_MODE, choices=["A", "B"],
                        help="graph mode. A=Bull/Bear 토론(prod), B=단일 에이전트(ablation). 기본 A")
    parser.add_argument("--windows", default="3,7,14",
                        help="비교할 일 단위 윈도우 (콤마 구분). 기본 '3,7,14'")
    parser.add_argument("--threshold", type=float, default=DEFAULT_AGREEMENT_THRESHOLD,
                        help=f"일치율 임계값 (0~1). 기본 {DEFAULT_AGREEMENT_THRESHOLD}")
    parser.add_argument("--repeat", type=int, default=DEFAULT_REPEAT,
                        help=f"각 샘플당 agent 호출 반복 (다수결). 기본 {DEFAULT_REPEAT}")
    parser.add_argument("--out-json", type=Path, default=None,
                        help="상세 per-sample 결과를 JSON 으로 저장할 경로 (선택)")
    args = parser.parse_args()

    # ai_agent deps 누락 시 친절한 안내 후 종료.
    if run_pipeline is None:
        sys.stderr.write(
            "\n[ERROR] ai_agent 모듈 import 실패 — 검증 불가\n"
            f"    {_AI_AGENT_IMPORT_ERROR}\n\n"
            "  ai_agent 의 langgraph + langchain stack 이 필요합니다. analysis_server\n"
            "  venv 안에서 한 번 설치하세요:\n\n"
            "      .venv/Scripts/pip install -r ../ai_agent/requirements.txt\n\n"
            "  설치 후 다시 실행하세요.\n"
        )
        sys.exit(2)

    windows = tuple(int(x) for x in args.windows.split(","))
    samples = load_samples(args.csv)
    if not samples:
        sys.stderr.write(f"[ERROR] CSV 에 유효한 샘플 없음: {args.csv}\n")
        sys.exit(2)

    logger.info("샘플 %d건 / windows=%s / threshold=%.2f / repeat=%d / user_id=%d / mode=%s",
                len(samples), windows, args.threshold, args.repeat, args.user_id, args.mode)

    results = []
    for i, s in enumerate(samples, 1):
        logger.info("[%d/%d] stock=%s ts=%s rules=%s",
                    i, len(samples), s["stock_code"], s["timestamp"].isoformat(),
                    ",".join(s["rule_ids"]))
        results.append(evaluate_sample(s, windows, args.repeat, args.user_id, args.mode))

    print_report(results, windows, args.threshold, args.out_json)


if __name__ == "__main__":
    main()
