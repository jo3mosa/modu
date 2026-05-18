"""scripts/backtest/triggers_map_generator.py

Phase 4-1: AI Agent 입력용 trigger 매핑 JSON 생성.

입력:
    finalists.csv      — 23·24 모두 통과한 11개 가설
    bootstrap_ci.csv   — 채택 가설의 effect size + 95% CI
    stats_2023.csv     — A 단독 19개의 23년 measurement (baseline)

분류:
    Lv.3 (강한)    |effect| ≥ 5%          — AI Agent 매매 강하게 반영
    Lv.2 (중강)    2% ≤ |effect| < 5%     — 의미 있는 신호
    Lv.1 (보통)    1% ≤ |effect| < 2%     — 보조 신호
    Lv.0 (baseline) A 단독 19개            — 정보 차원, 약한 영향
    폐기            23년 통과·24년 미통과 가설 → 매핑에 포함 X

산출: triggers_map.json — AI Agent 가 import 해서 트리거 발화 시 Lv 조회.

사용:
    python -m scripts.backtest.triggers_map_generator
"""

from __future__ import annotations

import argparse
import json
import logging
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import pandas as pd

from scripts.backtest.hypotheses import ALL_HYPOTHESES, HYPOTHESES_BY_ID

logger = logging.getLogger(__name__)

# Lv 임계값 — effect size 절댓값 기준 (합본 mean).
LV3_THRESHOLD = 0.05
LV2_THRESHOLD = 0.02
LV1_THRESHOLD = 0.01

# 표본 작아 신뢰성 경고가 필요한 임계값.
LOW_SAMPLE_THRESHOLD = 300


# ────────────────────────────────────────────────────────────────────
# Lv 분류
# ────────────────────────────────────────────────────────────────────

def classify_lv_for_combination(effect_mean: float) -> int:
    """B/C 조합 가설의 effect size → Lv (1/2/3).

    검정 통과했지만 effect size 매우 작은 경우 (< LV1_THRESHOLD) → Lv.0 으로 격하.
    """
    if effect_mean is None or pd.isna(effect_mean):
        return 0
    a = abs(effect_mean)
    if a >= LV3_THRESHOLD:
        return 3
    if a >= LV2_THRESHOLD:
        return 2
    if a >= LV1_THRESHOLD:
        return 1
    return 0


# ────────────────────────────────────────────────────────────────────
# 매핑 빌더
# ────────────────────────────────────────────────────────────────────

def _entry_for_combination(hid: str, boot_row: pd.Series) -> dict:
    """B/C 채택 가설 entry."""
    h = HYPOTHESES_BY_ID[hid]
    entry: dict[str, Any] = {
        "id": hid,
        "name": h.name,
        "category": h.category,
        "kind": h.kind,
        "components": h.components,
        "expected_direction": h.expected_direction,
        "main_horizon": h.main_horizon,
        "effect_mean": round(float(boot_row["mean"]), 5),
        "ci_95_low":  round(float(boot_row["ci_low"]),  5),
        "ci_95_high": round(float(boot_row["ci_high"]), 5),
        "n_events": int(boot_row["n"]),
        "rationale": h.rationale,
    }
    if int(boot_row["n"]) < LOW_SAMPLE_THRESHOLD:
        entry["warning"] = "low_sample"
    return entry


def _entry_for_single(hid: str, stats_row: dict) -> dict:
    """A 단독 가설 entry — Lv.0 baseline 정보용."""
    h = HYPOTHESES_BY_ID[hid]
    mean = stats_row.get("mean")
    return {
        "id": hid,
        "name": h.name,
        "category": h.category,
        "kind": h.kind,
        "components": h.components,
        "expected_direction": h.expected_direction,
        "main_horizon": h.main_horizon,
        "effect_mean_2023": round(float(mean), 5) if pd.notna(mean) else None,
        "n_events_2023": int(stats_row.get("n_labeled") or 0),
        "win_rate_2023": round(float(stats_row.get("win_rate") or 0), 4),
        "rationale": h.rationale,
        "note": "측정만 (검정 X). baseline 정보 신호.",
    }


def build_map(finalists_path: str, bootstrap_ci_path: str,
              stats_2023_path: str) -> dict:
    # finalists.csv 가 "채택 가설" 단일 소스. bootstrap_ci.csv 에 비-finalist
    # 행이 섞여 있어도 finalists 로만 좁혀 매핑 누락 / 잉여 위험 차단.
    finals = pd.read_csv(finalists_path)
    finalist_ids = set(finals["id"].astype(str))

    boot = pd.read_csv(bootstrap_ci_path)
    boot_all = (
        boot[(boot["period"] == "all") & (boot["id"].astype(str).isin(finalist_ids))]
        .set_index("id")
    )
    logger.info("채택 가설(B/C) %d개 + A 단독 baseline 19개", len(boot_all))

    # A 단독 stats.
    stats_23 = pd.read_csv(stats_2023_path).set_index("id")

    # Lv 버킷.
    by_lv: dict[int, list[dict]] = {0: [], 1: [], 2: [], 3: []}

    # 1) 채택 B/C → Lv.1/2/3 분류.
    for hid, row in boot_all.iterrows():
        lv = classify_lv_for_combination(row["mean"])
        entry = _entry_for_combination(hid, row)
        by_lv[lv].append(entry)
        logger.info("  Lv.%d: %s (%s, effect=%+.4f, n=%d)",
                    lv, hid, HYPOTHESES_BY_ID[hid].name,
                    row["mean"], int(row["n"]))

    # 2) A 단독 19개 → Lv.0 일괄.
    for h in ALL_HYPOTHESES:
        if h.category != "single":
            continue
        s_row = stats_23.loc[h.id].to_dict() if h.id in stats_23.index else {}
        entry = _entry_for_single(h.id, s_row)
        by_lv[0].append(entry)

    # 각 Lv 내부 정렬: |effect| 내림차순.
    def _abs_key(e):
        v = e.get("effect_mean") or e.get("effect_mean_2023") or 0
        return -abs(v)
    for lv in by_lv:
        by_lv[lv].sort(key=_abs_key)

    payload = {
        "version": "1.0",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "data_period": "2023-01-01 ~ 2024-12-31",
        "methodology": {
            "split": "23 탐색 / 24 out-of-sample 검증",
            "alpha": 0.05,
            "correction": "Benjamini-Hochberg FDR",
            "ci_method": "bootstrap n=10000, 95%",
            "horizon_default": "T+5 거래일 (펀더멘털 가설은 T+20)",
            "lv_thresholds": {
                "Lv.3": f"|effect_mean| ≥ {LV3_THRESHOLD}",
                "Lv.2": f"{LV2_THRESHOLD} ≤ |effect_mean| < {LV3_THRESHOLD}",
                "Lv.1": f"{LV1_THRESHOLD} ≤ |effect_mean| < {LV2_THRESHOLD}",
                "Lv.0": "A 단독 19개 (baseline 정보 차원)",
            },
            "trigger_definitions": {
                "transition": "어제 임계 외, 오늘 임계 내 (예: RSI<30 진입). 첫 진입일 1회만 발화.",
                "is_market_event_true": "공시·뉴스 — 라벨링 시 장중 발생은 익일 시가 진입.",
                "is_market_event_false": "기술 transition — T+1 시가 진입.",
            },
        },
        "counts": {
            "Lv.3": len(by_lv[3]),
            "Lv.2": len(by_lv[2]),
            "Lv.1": len(by_lv[1]),
            "Lv.0_baseline": len(by_lv[0]),
            "total": sum(len(v) for v in by_lv.values()),
        },
        "triggers": {
            "Lv.3": by_lv[3],
            "Lv.2": by_lv[2],
            "Lv.1": by_lv[1],
            "Lv.0": by_lv[0],
        },
    }
    return payload


# ────────────────────────────────────────────────────────────────────
# 실행
# ────────────────────────────────────────────────────────────────────

def print_summary(payload: dict) -> None:
    counts = payload["counts"]
    print("\n" + "=" * 80)
    print("Phase 4-1: triggers_map.json 생성 완료")
    print("=" * 80)
    print("\n분포:")
    for lv in ["Lv.3", "Lv.2", "Lv.1", "Lv.0_baseline"]:
        print(f"  {lv:<18} {counts[lv]:>3}개")
    print(f"  {'total':<18} {counts['total']:>3}개")

    print("\n[채택 B/C 가설 — AI Agent 매매 신호로 활용]")
    for lv_key in ["Lv.3", "Lv.2", "Lv.1"]:
        items = payload["triggers"][lv_key]
        if not items:
            continue
        print(f"\n  {lv_key} ({len(items)}개)")
        for it in items:
            warn = f"  ⚠ {it.get('warning')}" if it.get("warning") else ""
            ci = f"[{it['ci_95_low']:+.4f}, {it['ci_95_high']:+.4f}]"
            print(f"    {it['id']:<5} {it['name']:<32}  effect={it['effect_mean']:+.4f}  CI={ci}  n={it['n_events']}{warn}")

    print(f"\n[Lv.0 A 단독 baseline — {len(payload['triggers']['Lv.0'])}개]")
    print("  (효과 측정만 — AI Agent 가 정보 신호로 참조)")


def main():
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
        datefmt="%H:%M:%S",
    )
    parser = argparse.ArgumentParser()
    parser.add_argument("--finalists",     default="finalists.csv")
    parser.add_argument("--bootstrap-ci",  default="bootstrap_ci.csv")
    parser.add_argument("--stats-2023",    default="stats_2023.csv")
    parser.add_argument("--output",        default="triggers_map.json")
    args = parser.parse_args()

    payload = build_map(args.finalists, args.bootstrap_ci, args.stats_2023)

    out = Path(args.output)
    out.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    print_summary(payload)
    print(f"\n저장: {out} ({out.stat().st_size / 1024:.1f} KB)")


if __name__ == "__main__":
    main()
