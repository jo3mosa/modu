"""scripts/backtest/explore.py

Phase 3-1: 23년 탐색 단계.

events_<year>.parquet → 가설별 라벨링 → 통계 → FDR 보정 → 통과 가설 추리기.

가설 분류별 처리:
    - A 단독 (test_status=measurement_only):
        효과 측정만. Lv.0 baseline 으로 보존. FDR 보정 대상 X.
    - B/C 조합 (test_status=hypothesis_test):
        t-test 로 p-value 산출 + Benjamini-Hochberg FDR 5% 보정.
        통과한 가설만 24년 검증 단계로 넘김.

방향성 검정:
    - expected_direction=positive  → 단측 (오른쪽 꼬리)  H1: mean > 0
    - expected_direction=negative  → 단측 (왼쪽 꼬리)   H1: mean < 0
    - expected_direction=two_sided → 양측              H1: mean ≠ 0

산출:
    stats_<year>.csv  — 가설별 통계 표 (전체)
    survivors_<year>.csv — FDR 통과 가설만 (24년 검증 후보)
    콘솔 — 카테고리별 요약 표 + 통과 가설 명단

사용:
    DB_HOST=localhost python -m scripts.backtest.explore \\
        --input events_2023.parquet --alpha 0.05
"""

from __future__ import annotations

import argparse
import logging
from pathlib import Path

import numpy as np
import pandas as pd
from scipy import stats as sps

from scripts.backtest.hypotheses import ALL_HYPOTHESES, HYPOTHESES_BY_ID
from scripts.backtest.labeling import label_events_batch

logger = logging.getLogger(__name__)


# ────────────────────────────────────────────────────────────────────
# 단일 가설 평가
# ────────────────────────────────────────────────────────────────────

def evaluate_hypothesis(events_df: pd.DataFrame, hypothesis_id: str,
                        horizons: tuple = (1, 5, 20)) -> dict:
    """단일 가설 이벤트들 라벨링 + 통계 산출.

    main_horizon (가설별 정의된 평가 horizon) 기준 통계만 검정에 사용.
    보조 horizon mean 도 sensitivity 참고용으로 함께 출력.
    """
    h = HYPOTHESES_BY_ID[hypothesis_id]
    base = {
        "id": hypothesis_id,
        "name": h.name,
        "category": h.category,
        "expected_direction": h.expected_direction,
        "main_horizon": h.main_horizon,
        "test_status": h.test_status,
    }

    sub = events_df[events_df["hypothesis_id"] == hypothesis_id]
    n_events = len(sub)
    if n_events == 0:
        return {**base, "n_events": 0, **{f"ret_T{h_}_mean": None for h_ in horizons},
                "n_labeled": 0, "mean": None, "std": None,
                "win_rate": None, "t_stat": None, "p_value": None,
                "effect_size": None}

    # 라벨링 — 가설 안의 모든 이벤트.
    sub_renamed = sub[["stock_code", "event_ts", "is_market_event"]].copy()
    labeled = label_events_batch(sub_renamed, horizons=horizons)

    # 보조 horizon mean (참고용).
    aux_means = {f"ret_T{h_}_mean": float(labeled[f"ret_T{h_}"].dropna().mean())
                 if labeled[f"ret_T{h_}"].notna().any() else None
                 for h_ in horizons}

    # 메인 horizon 기준 통계.
    main_col = f"ret_T{h.main_horizon}"
    rets = labeled[main_col].dropna()
    n_labeled = len(rets)
    if n_labeled < 2:
        return {**base, "n_events": n_events, **aux_means,
                "n_labeled": n_labeled, "mean": None, "std": None,
                "win_rate": None, "t_stat": None, "p_value": None,
                "effect_size": None}

    mean = float(rets.mean())
    std = float(rets.std(ddof=1))
    win_rate = float((rets > 0).mean())

    if std > 0:
        t_stat = mean / (std / np.sqrt(n_labeled))
        dof = n_labeled - 1
        if h.expected_direction == "positive":
            p_value = float(sps.t.sf(t_stat, dof))      # H1: mean > 0
        elif h.expected_direction == "negative":
            p_value = float(sps.t.cdf(t_stat, dof))     # H1: mean < 0
        else:  # two_sided
            p_value = float(2 * sps.t.sf(abs(t_stat), dof))
        effect_size = mean / std                         # Cohen's d
    else:
        t_stat = 0.0
        p_value = 1.0
        effect_size = 0.0

    return {**base, "n_events": n_events, **aux_means,
            "n_labeled": n_labeled,
            "mean": mean, "std": std, "win_rate": win_rate,
            "t_stat": float(t_stat), "p_value": p_value,
            "effect_size": float(effect_size)}


# ────────────────────────────────────────────────────────────────────
# Benjamini-Hochberg FDR
# ────────────────────────────────────────────────────────────────────

def benjamini_hochberg(p_values: np.ndarray, alpha: float = 0.05):
    """BH FDR 보정. 반환: (passed: bool array, q_values: array).

    q_value = adjusted p-value. q < alpha 이면 통과.
    """
    p = np.asarray(p_values, dtype=float)
    n = len(p)
    if n == 0:
        return np.zeros(0, dtype=bool), np.zeros(0)

    order = np.argsort(p)
    p_sorted = p[order]
    # BH critical: (i / m) * alpha
    ranks = np.arange(1, n + 1)
    critical = (ranks / n) * alpha
    is_sig = p_sorted <= critical

    # 가장 큰 i 까지 모두 통과.
    if is_sig.any():
        max_i = int(np.where(is_sig)[0].max())
        passed_sorted = np.zeros(n, dtype=bool)
        passed_sorted[: max_i + 1] = True
    else:
        passed_sorted = np.zeros(n, dtype=bool)

    # q-values: adjusted p.
    q_raw = p_sorted * n / ranks
    # monotone non-decreasing 보정 (BH 표준): 뒤에서부터 누적 min.
    q_mono = np.minimum.accumulate(q_raw[::-1])[::-1]
    q_mono = np.minimum(q_mono, 1.0)

    # 원래 순서로 복원.
    passed = np.zeros(n, dtype=bool)
    q_values = np.zeros(n)
    for i, idx in enumerate(order):
        passed[idx] = passed_sorted[i]
        q_values[idx] = q_mono[i]
    return passed, q_values


# ────────────────────────────────────────────────────────────────────
# 전체 흐름
# ────────────────────────────────────────────────────────────────────

def evaluate_all(events_parquet_path: str, alpha: float = 0.05) -> pd.DataFrame:
    df = pd.read_parquet(events_parquet_path)
    logger.info("로드: %s — %d events / %d 가설",
                events_parquet_path, len(df), df["hypothesis_id"].nunique())

    rows = []
    for h in ALL_HYPOTHESES:
        logger.info("평가 중: %s (%s)", h.id, h.name)
        rows.append(evaluate_hypothesis(df, h.id))
    stats_df = pd.DataFrame(rows)

    # FDR 보정 — hypothesis_test 만.
    test_mask = stats_df["test_status"] == "hypothesis_test"
    p_arr = stats_df.loc[test_mask, "p_value"].values

    stats_df["fdr_passed"] = False
    stats_df["q_value"] = np.nan

    # NaN p-value (라벨링 표본 부족 등) 는 보정에서 제외.
    valid_p_mask = test_mask & stats_df["p_value"].notna()
    valid_p = stats_df.loc[valid_p_mask, "p_value"].values
    if len(valid_p) > 0:
        passed, q = benjamini_hochberg(valid_p, alpha=alpha)
        stats_df.loc[valid_p_mask, "fdr_passed"] = passed
        stats_df.loc[valid_p_mask, "q_value"] = q

    return stats_df


def print_summary(stats_df: pd.DataFrame, alpha: float) -> None:
    """카테고리별 요약 + 통과 가설 명단."""
    print("\n" + "=" * 100)
    print(f"평가 결과 요약 (FDR alpha={alpha})")
    print("=" * 100)

    # ── A 단독 (측정만) ──
    a_df = stats_df[stats_df["test_status"] == "measurement_only"].copy()
    print(f"\n[A 단독 — Lv.0 baseline 측정용 (검정 X) — {len(a_df)}개]")
    cols = ["id", "name", "n_labeled", "mean", "std", "win_rate", "t_stat", "effect_size"]
    a_view = a_df[cols].copy()
    for c in ["mean", "std", "effect_size"]:
        a_view[c] = a_view[c].apply(lambda v: f"{v:.4f}" if v is not None and not pd.isna(v) else "-")
    a_view["win_rate"] = a_view["win_rate"].apply(lambda v: f"{v*100:.1f}%" if v is not None and not pd.isna(v) else "-")
    a_view["t_stat"] = a_view["t_stat"].apply(lambda v: f"{v:+.2f}" if v is not None and not pd.isna(v) else "-")
    print(a_view.to_string(index=False))

    # ── B/C (검정 + FDR) ──
    bc_df = stats_df[stats_df["test_status"] == "hypothesis_test"].copy()
    print(f"\n[B/C 검정 대상 — {len(bc_df)}개, FDR 5% 보정]")
    cols = ["id", "name", "expected_direction", "n_labeled",
            "mean", "win_rate", "t_stat", "p_value", "q_value", "fdr_passed"]
    bc_view = bc_df[cols].copy()
    bc_view["mean"] = bc_view["mean"].apply(lambda v: f"{v:+.4f}" if v is not None and not pd.isna(v) else "-")
    bc_view["win_rate"] = bc_view["win_rate"].apply(lambda v: f"{v*100:.1f}%" if v is not None and not pd.isna(v) else "-")
    bc_view["t_stat"] = bc_view["t_stat"].apply(lambda v: f"{v:+.2f}" if v is not None and not pd.isna(v) else "-")
    bc_view["p_value"] = bc_view["p_value"].apply(lambda v: f"{v:.4f}" if v is not None and not pd.isna(v) else "-")
    bc_view["q_value"] = bc_view["q_value"].apply(lambda v: f"{v:.4f}" if v is not None and not pd.isna(v) else "-")
    bc_view["fdr_passed"] = bc_view["fdr_passed"].apply(lambda v: "✓" if v else "")
    bc_view = bc_view.rename(columns={"expected_direction": "dir"})
    print(bc_view.to_string(index=False))

    # ── 통과 가설 명단 ──
    survivors = stats_df[stats_df["fdr_passed"]].copy()
    print(f"\n[FDR {alpha} 통과 가설 — {len(survivors)}개 / {len(bc_df)} 검정대상]")
    if len(survivors) > 0:
        surv_cols = ["id", "name", "expected_direction", "n_labeled",
                     "mean", "t_stat", "p_value", "q_value", "effect_size"]
        s_view = survivors[surv_cols].copy()
        s_view["mean"] = s_view["mean"].apply(lambda v: f"{v:+.4f}")
        s_view["t_stat"] = s_view["t_stat"].apply(lambda v: f"{v:+.2f}")
        s_view["p_value"] = s_view["p_value"].apply(lambda v: f"{v:.4f}")
        s_view["q_value"] = s_view["q_value"].apply(lambda v: f"{v:.4f}")
        s_view["effect_size"] = s_view["effect_size"].apply(lambda v: f"{v:+.3f}")
        print(s_view.to_string(index=False))
        print("\n→ 위 가설들이 24년 검증 단계(Phase 3-2)로 넘어갑니다.")
    else:
        print("(없음 — FDR 통과 가설 0개. 임계값/horizon 재검토 필요할 수 있음)")


def main():
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
        datefmt="%H:%M:%S",
    )
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True,
                        help="events parquet 경로 (예: events_2023.parquet)")
    parser.add_argument("--output", default=None,
                        help="통계 CSV 출력 경로 (기본: stats_<year>.csv)")
    parser.add_argument("--survivors", default=None,
                        help="FDR 통과 가설 CSV 출력 경로 (기본: survivors_<year>.csv)")
    parser.add_argument("--alpha", type=float, default=0.05,
                        help="FDR 보정 alpha (기본 0.05)")
    args = parser.parse_args()

    stats_df = evaluate_all(args.input, alpha=args.alpha)

    in_stem = Path(args.input).stem
    year_part = in_stem.replace("events_", "")
    stats_out = args.output or f"stats_{year_part}.csv"
    surv_out = args.survivors or f"survivors_{year_part}.csv"

    stats_df.to_csv(stats_out, index=False, encoding="utf-8-sig")
    survivors = stats_df[stats_df["fdr_passed"]]
    survivors.to_csv(surv_out, index=False, encoding="utf-8-sig")

    print_summary(stats_df, args.alpha)

    print(f"\n저장:\n  전체 통계 → {stats_out}\n  통과 가설 → {surv_out}")


if __name__ == "__main__":
    main()
