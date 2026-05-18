"""scripts/backtest/validate.py

Phase 3-2: 24년 검증.

23년 FDR 통과 가설(survivors_2023.csv) 을 24년 데이터(events_2024.parquet) 에 적용.
같은 방향 + 24년에서도 FDR 5% 통과 → **최종 채택**.

핵심:
    - 23년 결과는 후보 선정용 (overfitting 위험)
    - 24년 out-of-sample 통과해야 진짜 효과로 인정
    - 23년 부호와 24년 부호 일치 검사도 추가 — 부호 뒤집히면 우연으로 분류

산출:
    validation_2024.csv  — 13개 후보의 24년 평가 + 23·24 비교
    finalists.csv        — 최종 채택 가설 (24년 통과 + 방향 일치)
"""

from __future__ import annotations

import argparse
import logging
from pathlib import Path

import numpy as np
import pandas as pd

from scripts.backtest.explore import benjamini_hochberg, evaluate_hypothesis
from scripts.backtest.hypotheses import HYPOTHESES_BY_ID

logger = logging.getLogger(__name__)


def validate(events_2024_path: str,
             survivors_2023_path: str,
             alpha: float = 0.05) -> pd.DataFrame:
    """23년 통과 가설을 24년에 재평가.

    Returns:
        DataFrame with columns:
            id, name, expected_direction,
            n_labeled_23, mean_23, t_stat_23, p_value_23,
            n_labeled_24, mean_24, t_stat_24, p_value_24, q_value_24,
            direction_match, fdr_passed_24, final_accepted
    """
    surv = pd.read_csv(survivors_2023_path)
    if surv.empty:
        logger.warning("survivors_2023.csv 가 비어 있음 — 24년 검증할 가설 없음")
        return pd.DataFrame()

    # 23년 결과 (검증에 비교용으로 보존).
    s23 = surv.set_index("id")
    candidates = s23.index.tolist()
    logger.info("23년 통과 가설 %d개를 24년에서 검증", len(candidates))

    # 24년 events 로드 + 가설별 평가.
    events_24 = pd.read_parquet(events_2024_path)
    logger.info("24년 events: %d rows", len(events_24))

    rows_24 = []
    for hid in candidates:
        logger.info("평가 중: %s", hid)
        stats = evaluate_hypothesis(events_24, hid)
        rows_24.append(stats)
    val = pd.DataFrame(rows_24)

    # 24년 FDR 보정 (13개라 부담 적음).
    valid_p = val["p_value"].notna()
    if valid_p.any():
        passed_24, q_24 = benjamini_hochberg(val.loc[valid_p, "p_value"].values,
                                              alpha=alpha)
    else:
        passed_24 = np.zeros(0, dtype=bool)
        q_24 = np.zeros(0)
    val["fdr_passed_24"] = False
    val["q_value_24"] = np.nan
    val.loc[valid_p, "fdr_passed_24"] = passed_24
    val.loc[valid_p, "q_value_24"] = q_24

    # 23·24 비교 컬럼 추가.
    val["mean_23"] = val["id"].map(s23["mean"])
    val["n_labeled_23"] = val["id"].map(s23["n_labeled"])
    val["t_stat_23"] = val["id"].map(s23["t_stat"])
    val["p_value_23"] = val["id"].map(s23["p_value"])

    # 방향 일치 검사 — 23년 mean 부호 == 24년 mean 부호.
    val["direction_match"] = (
        val["mean"].notna()
        & val["mean_23"].notna()
        & (np.sign(val["mean"]) == np.sign(val["mean_23"]))
    )

    # 최종 채택: 24년 FDR 통과 + 방향 일치.
    val["final_accepted"] = val["fdr_passed_24"] & val["direction_match"]

    # 24년 컬럼명 명확화.
    val = val.rename(columns={
        "n_labeled": "n_labeled_24",
        "mean": "mean_24",
        "t_stat": "t_stat_24",
        "p_value": "p_value_24",
        "win_rate": "win_rate_24",
        "effect_size": "effect_size_24",
    })

    return val


def print_summary(val_df: pd.DataFrame, alpha: float) -> None:
    if val_df.empty:
        print("검증 대상 없음")
        return

    print("\n" + "=" * 110)
    print(f"Phase 3-2 24년 검증 결과 (FDR alpha={alpha})")
    print("=" * 110)

    # 23·24 비교 표.
    view = val_df[[
        "id", "name", "expected_direction",
        "n_labeled_23", "mean_23", "p_value_23",
        "n_labeled_24", "mean_24", "p_value_24", "q_value_24",
        "direction_match", "fdr_passed_24", "final_accepted",
    ]].copy()
    view["mean_23"] = view["mean_23"].apply(lambda v: f"{v:+.4f}" if pd.notna(v) else "-")
    view["mean_24"] = view["mean_24"].apply(lambda v: f"{v:+.4f}" if pd.notna(v) else "-")
    view["p_value_23"] = view["p_value_23"].apply(lambda v: f"{v:.4f}" if pd.notna(v) else "-")
    view["p_value_24"] = view["p_value_24"].apply(lambda v: f"{v:.4f}" if pd.notna(v) else "-")
    view["q_value_24"] = view["q_value_24"].apply(lambda v: f"{v:.4f}" if pd.notna(v) else "-")
    view["direction_match"] = view["direction_match"].apply(lambda v: "✓" if v else "✗")
    view["fdr_passed_24"] = view["fdr_passed_24"].apply(lambda v: "✓" if v else "")
    view["final_accepted"] = view["final_accepted"].apply(lambda v: "★" if v else "")
    view = view.rename(columns={
        "expected_direction": "dir",
        "direction_match": "dir_OK",
        "fdr_passed_24": "FDR_24",
        "final_accepted": "최종",
    })
    print(view.to_string(index=False))

    # 최종 채택.
    finals = val_df[val_df["final_accepted"]].copy()
    n_total = len(val_df)
    n_final = len(finals)
    print(f"\n[★ 최종 채택 — {n_final}개 / 23년 통과 {n_total}개]")
    if n_final > 0:
        cols = ["id", "name", "expected_direction",
                "mean_23", "mean_24",
                "effect_size_24", "n_labeled_24", "q_value_24"]
        f_view = finals[cols].copy()
        f_view["mean_23"] = f_view["mean_23"].apply(lambda v: f"{v:+.4f}")
        f_view["mean_24"] = f_view["mean_24"].apply(lambda v: f"{v:+.4f}")
        f_view["effect_size_24"] = f_view["effect_size_24"].apply(lambda v: f"{v:+.3f}")
        f_view["q_value_24"] = f_view["q_value_24"].apply(lambda v: f"{v:.4f}")
        print(f_view.to_string(index=False))
        print("\n→ 이 가설들이 Phase 3-3 (Bootstrap CI) + Phase 4-1 (트리거 매핑) 으로 진행됩니다.")
    else:
        print("(없음 — 23년 통과 가설이 24년에 모두 떨어짐. 23년 결과가 우연이었을 가능성)")

    # 떨어진 가설 — 사유 분석.
    failed = val_df[~val_df["final_accepted"]].copy()
    if len(failed) > 0:
        print(f"\n[24년 미통과 — {len(failed)}개]")
        for _, r in failed.iterrows():
            reasons = []
            if pd.isna(r["mean_24"]):
                reasons.append("24년 라벨링 표본 부족")
            elif not r["direction_match"]:
                reasons.append("방향 뒤집힘 (23↔24 부호 불일치)")
            elif not r["fdr_passed_24"]:
                reasons.append(f"24년 FDR 미통과 (q={r['q_value_24']:.3f})")
            print(f"  {r['id']} {r['name']}: {'; '.join(reasons)}")


def main():
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
        datefmt="%H:%M:%S",
    )
    parser = argparse.ArgumentParser()
    parser.add_argument("--events-2024", default="events_2024.parquet")
    parser.add_argument("--survivors-2023", default="survivors_2023.csv")
    parser.add_argument("--alpha", type=float, default=0.05)
    parser.add_argument("--output", default="validation_2024.csv")
    parser.add_argument("--finalists", default="finalists.csv")
    args = parser.parse_args()

    val_df = validate(args.events_2024, args.survivors_2023, alpha=args.alpha)
    if val_df.empty:
        return

    val_df.to_csv(args.output, index=False, encoding="utf-8-sig")
    finals = val_df[val_df["final_accepted"]]
    finals.to_csv(args.finalists, index=False, encoding="utf-8-sig")

    print_summary(val_df, args.alpha)
    print(f"\n저장:\n  검증 표 → {args.output}\n  최종 채택 → {args.finalists}")


if __name__ == "__main__":
    main()
