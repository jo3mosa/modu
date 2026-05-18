"""scripts/backtest/bootstrap_ci.py

Phase 3-3: Bootstrap 95% 신뢰구간.

최종 채택 가설(finalists.csv) 의 effect size(평균 수익률) 에 대한 신뢰구간을
복원 추출 bootstrap 으로 추정.

세 가지 기간 비교:
    - 2023 단독  (탐색 기간 효과)
    - 2024 단독  (검증 기간 효과)
    - 23+24 합본 (가장 robust 한 점추정)

해석:
    CI 가 0 을 포함하지 않음 → 효과의 부호가 신뢰할 만함 (excludes_zero=True)
    CI 가 좁음                → 표본이 충분, 효과 추정 안정적
    CI 가 매우 넓음            → 표본 작거나 변동성 큼, 효과 추정 불안정

산출:
    bootstrap_ci.csv — 가설 × 기간별 (n, mean, ci_low, ci_high)
"""

from __future__ import annotations

import argparse
import logging

import numpy as np
import pandas as pd

from scripts.backtest.hypotheses import HYPOTHESES_BY_ID
from scripts.backtest.labeling import label_events_batch

logger = logging.getLogger(__name__)

# Bootstrap iteration default — 1만이면 백분위 안정. n_iter × n 메모리 부담 시 청크 처리.
DEFAULT_N_ITER = 10_000

# Vectorized bootstrap 의 메모리 cap. n_iter × n × 8byte 가 이 값 초과하면 청크.
MAX_MEMORY_BYTES = 800 * 1024 * 1024   # 800MB


# ────────────────────────────────────────────────────────────────────
# Bootstrap 계산
# ────────────────────────────────────────────────────────────────────

def bootstrap_mean_ci(returns: np.ndarray,
                      n_iter: int = DEFAULT_N_ITER,
                      ci: float = 0.95,
                      seed: int = 42) -> tuple:
    """수익률 array → (mean, ci_low, ci_high). 표본 부족이면 (None, None, None)."""
    rets = np.asarray(returns, dtype=float)
    n = len(rets)
    if n < 30:
        return (None, None, None)

    rng = np.random.default_rng(seed)

    # 메모리 부담 시 청크 처리.
    bytes_per_iter = n * 8
    chunk_size = max(1, int(MAX_MEMORY_BYTES / bytes_per_iter))

    if chunk_size >= n_iter:
        idx = rng.integers(0, n, size=(n_iter, n))
        means = rets[idx].mean(axis=1)
    else:
        means_list = []
        remaining = n_iter
        while remaining > 0:
            this_chunk = min(chunk_size, remaining)
            idx = rng.integers(0, n, size=(this_chunk, n))
            means_list.append(rets[idx].mean(axis=1))
            remaining -= this_chunk
        means = np.concatenate(means_list)

    alpha = (1 - ci) / 2
    low = float(np.percentile(means, alpha * 100))
    high = float(np.percentile(means, (1 - alpha) * 100))
    return (float(rets.mean()), low, high)


# ────────────────────────────────────────────────────────────────────
# 가설별 평가
# ────────────────────────────────────────────────────────────────────

def _label_finalists(events_path: str,
                     finalist_ids: list[str]) -> pd.DataFrame:
    """events parquet → finalist 가설들만 일괄 라벨링."""
    events = pd.read_parquet(events_path)
    sub = events[events["hypothesis_id"].isin(finalist_ids)]
    if sub.empty:
        return pd.DataFrame()
    keep_cols = ["hypothesis_id", "stock_code", "event_ts", "is_market_event"]
    labeled = label_events_batch(sub[keep_cols].copy(), horizons=(1, 5, 20))
    return labeled


def _ci_row(labeled: pd.DataFrame, hid: str, n_iter: int) -> dict:
    h = HYPOTHESES_BY_ID[hid]
    main_col = f"ret_T{h.main_horizon}"
    sub = labeled[labeled["hypothesis_id"] == hid]
    rets = sub[main_col].dropna().values
    mean, low, high = bootstrap_mean_ci(rets, n_iter=n_iter)
    excludes_zero = (
        mean is not None and low is not None and high is not None
        and ((low > 0) or (high < 0))
    )
    return {
        "n": len(rets),
        "mean": mean, "ci_low": low, "ci_high": high,
        "excludes_zero": excludes_zero,
    }


# ────────────────────────────────────────────────────────────────────
# 전체 흐름
# ────────────────────────────────────────────────────────────────────

def run(finalists_path: str, events_2023_path: str,
        events_2024_path: str, n_iter: int = DEFAULT_N_ITER) -> pd.DataFrame:
    finals = pd.read_csv(finalists_path)
    fids = finals["id"].tolist()
    logger.info("채택 가설 %d개에 대해 bootstrap CI 계산", len(fids))

    logger.info("23년 라벨링 중...")
    labeled_23 = _label_finalists(events_2023_path, fids)
    logger.info("24년 라벨링 중...")
    labeled_24 = _label_finalists(events_2024_path, fids)
    labeled_all = pd.concat([labeled_23, labeled_24], ignore_index=True)

    rows = []
    for hid in fids:
        h = HYPOTHESES_BY_ID[hid]
        for period_name, labeled in [
            ("2023", labeled_23),
            ("2024", labeled_24),
            ("all",  labeled_all),
        ]:
            r = _ci_row(labeled, hid, n_iter)
            r["id"] = hid
            r["name"] = h.name
            r["expected_direction"] = h.expected_direction
            r["main_horizon"] = h.main_horizon
            r["period"] = period_name
            rows.append(r)
        logger.info("  [%s] %s done", hid, h.name)

    cols = ["id", "name", "expected_direction", "main_horizon",
            "period", "n", "mean", "ci_low", "ci_high", "excludes_zero"]
    return pd.DataFrame(rows)[cols]


def print_summary(df: pd.DataFrame) -> None:
    print("\n" + "=" * 110)
    print("Phase 3-3 Bootstrap 95% CI — 채택 가설 effect size 신뢰구간")
    print("=" * 110)

    # 합본(all) 만 강조 — 가장 robust 한 점추정.
    all_df = df[df["period"] == "all"].copy()
    print("\n[합본 23+24 기준 — 가장 robust]")
    view = all_df[["id", "name", "expected_direction", "main_horizon",
                   "n", "mean", "ci_low", "ci_high", "excludes_zero"]].copy()
    for c in ["mean", "ci_low", "ci_high"]:
        view[c] = view[c].apply(
            lambda v: f"{v:+.4f}" if v is not None and not pd.isna(v) else "-"
        )
    view["excludes_zero"] = view["excludes_zero"].apply(lambda v: "✓" if v else "")
    view = view.rename(columns={
        "expected_direction": "dir",
        "main_horizon": "T",
        "excludes_zero": "CI≠0",
    })
    print(view.to_string(index=False))

    # 23·24 비교 — 효과 안정성 (CI 겹치는가).
    print("\n[기간 비교 — 효과 안정성]")
    comp = df[df["period"].isin(["2023", "2024"])].pivot(
        index=["id", "name"], columns="period",
        values=["mean", "ci_low", "ci_high"],
    )
    # 두 기간 CI 가 겹치는지: max(low) <= min(high) 면 겹침.
    stable_rows = []
    for (hid, name), row in comp.iterrows():
        try:
            m23, m24 = row[("mean", "2023")], row[("mean", "2024")]
            l23, l24 = row[("ci_low", "2023")], row[("ci_low", "2024")]
            h23, h24 = row[("ci_high", "2023")], row[("ci_high", "2024")]
            if any(pd.isna(x) for x in [m23, m24, l23, l24, h23, h24]):
                overlap = "—"
            else:
                overlap_check = (max(l23, l24) <= min(h23, h24))
                overlap = "✓ 겹침" if overlap_check else "✗ 안 겹침"
            stable_rows.append({
                "id": hid, "name": name,
                "mean_23": f"{m23:+.4f}" if pd.notna(m23) else "-",
                "mean_24": f"{m24:+.4f}" if pd.notna(m24) else "-",
                "CI_23": f"[{l23:+.4f}, {h23:+.4f}]" if pd.notna(l23) else "-",
                "CI_24": f"[{l24:+.4f}, {h24:+.4f}]" if pd.notna(l24) else "-",
                "CI 겹침": overlap,
            })
        except KeyError:
            continue
    stable_df = pd.DataFrame(stable_rows)
    print(stable_df.to_string(index=False))

    print("\n해석:")
    print("  • CI≠0 ✓ → 95% 신뢰로 효과가 0 이 아님 (방향 신뢰)")
    print("  • 기간 CI 겹침 ✓ → 23·24 효과 크기가 통계적으로 일관 (시기 robust)")
    print("  • CI 겹침 ✗ → 시기별 효과 변동 큼 (해석 주의)")


def main():
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
        datefmt="%H:%M:%S",
    )
    parser = argparse.ArgumentParser()
    parser.add_argument("--finalists",  default="finalists.csv")
    parser.add_argument("--events-2023", default="events_2023.parquet")
    parser.add_argument("--events-2024", default="events_2024.parquet")
    parser.add_argument("--n-iter", type=int, default=DEFAULT_N_ITER)
    parser.add_argument("--output", default="bootstrap_ci.csv")
    args = parser.parse_args()

    df = run(args.finalists, args.events_2023, args.events_2024, n_iter=args.n_iter)
    df.to_csv(args.output, index=False, encoding="utf-8-sig")
    print_summary(df)
    print(f"\n저장: {args.output}")


if __name__ == "__main__":
    main()
