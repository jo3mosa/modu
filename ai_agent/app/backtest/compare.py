"""모드별 backtest 비교 + 통계 검정.

같은 trigger set을 mode A / mode B / random에 흘려보내 JSONL 3개를 만들고,
event_id로 paired 매칭해 McNemar 검정 + bootstrap CI를 계산한다.

발표 슬라이드용 표를 그대로 stdout으로 찍는다.

사용 예:
    python -m app.backtest.compare \\
        --user-id 1 --stocks 005930 \\
        --start 2025-01-02 --end 2025-01-31 \\
        --output-dir runs/compare_2025_01
"""
import argparse
import json
import logging
from collections import defaultdict
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Any

from app.backtest.baselines import (
    DecisionProvider,
    GraphDecisionProvider,
    RandomDecisionProvider,
)
from app.backtest.replay_runner import BacktestConfig, StubPriceFetcher, run_backtest
from app.backtest.scorer import score_jsonl
from app.backtest.stats import bootstrap_ci, mcnemar_paired

logger = logging.getLogger(__name__)


@dataclass
class _ModeRecords:
    label: str
    hits_by_event: dict[str, bool]
    returns: list[float]
    n_traded: int
    n_holds: int


def _parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="MODU backtest 모드 비교 + 통계 검정")
    p.add_argument("--user-id", type=int, required=True)
    p.add_argument("--stocks", type=str, required=True)
    p.add_argument("--start", type=str, required=True)
    p.add_argument("--end", type=str, required=True)
    p.add_argument("--output-dir", type=Path, required=True)
    p.add_argument("--initial-cash", type=int, default=10_000_000)
    p.add_argument("--holding-days", type=int, default=7)
    p.add_argument("--random-seed", type=int, default=42)
    return p.parse_args()


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    args = _parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)

    base_config = BacktestConfig(
        user_id=args.user_id,
        stock_codes=[s.strip() for s in args.stocks.split(",") if s.strip()],
        start=date.fromisoformat(args.start),
        end=date.fromisoformat(args.end),
        output_path=Path(""),  # 모드별로 교체
        initial_cash=args.initial_cash,
    )

    providers: list[tuple[str, DecisionProvider]] = [
        ("A", GraphDecisionProvider(mode="A")),
        ("B", GraphDecisionProvider(mode="B")),
        ("random", RandomDecisionProvider(seed=args.random_seed)),
    ]

    jsonl_paths: dict[str, Path] = {}
    for label, provider in providers:
        out = args.output_dir / f"mode_{label}.jsonl"
        out.unlink(missing_ok=True)  # 누적 방지
        cfg = BacktestConfig(
            user_id=base_config.user_id,
            stock_codes=base_config.stock_codes,
            start=base_config.start,
            end=base_config.end,
            output_path=out,
            initial_cash=base_config.initial_cash,
        )
        logger.info("=== running mode %s ===", label)
        run_backtest(config=cfg, decision_provider=provider)
        jsonl_paths[label] = out

    price_fetcher = StubPriceFetcher()
    mode_records = {
        label: _build_records(label, path, price_fetcher, args.holding_days)
        for label, path in jsonl_paths.items()
    }

    _print_comparison_table(mode_records)
    _print_pairwise_mcnemar(mode_records)


def _build_records(
    label: str,
    jsonl_path: Path,
    price_fetcher: StubPriceFetcher,
    holding_days: int,
) -> _ModeRecords:
    """JSONL을 읽어 event_id → hit(bool) 매핑 + 수익률 시퀀스 생성."""
    hits: dict[str, bool] = {}
    returns: list[float] = []
    n_holds = 0
    n_traded = 0
    score = score_jsonl(jsonl_path, price_fetcher=price_fetcher, holding_days=holding_days)
    # score_jsonl은 총괄만 반환하므로 paired 비교를 위해 jsonl을 한 번 더 순회
    with jsonl_path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                rec = json.loads(line)
            except json.JSONDecodeError:
                continue
            event_id = rec.get("event_id")
            side = rec.get("side")
            entry = rec.get("execution_price")
            d = rec.get("date")
            if not event_id or rec.get("action") == "hold" or side not in ("buy", "sell"):
                n_holds += 1
                if event_id:
                    hits[event_id] = False
                continue
            if not entry or not d:
                continue
            n_traded += 1
            trade_date = date.fromisoformat(d)
            from app.backtest.scorer import _add_business_days  # noqa: PLC0415
            exit_date = _add_business_days(trade_date, holding_days)
            exit_price = price_fetcher.close_price(rec["stock_code"], exit_date)
            if not exit_price:
                continue
            ret = (exit_price - entry) / entry
            if side == "sell":
                ret = -ret
            returns.append(ret)
            hits[event_id] = ret > 0
    logger.info(
        "[%s] total=%d traded=%d holds=%d hit_rate=%.3f",
        label, score.total, n_traded, n_holds,
        sum(1 for v in hits.values() if v) / max(len([v for v in hits.values() if v is not None]), 1),
    )
    return _ModeRecords(
        label=label,
        hits_by_event=hits,
        returns=returns,
        n_traded=n_traded,
        n_holds=n_holds,
    )


def _print_comparison_table(mode_records: dict[str, _ModeRecords]) -> None:
    print()
    print(f"{'mode':<10}{'n_traded':>10}{'n_holds':>10}{'hit_rate':>12}{'avg_return':>14}{'ci_95':>20}")
    print("-" * 76)
    for label, rec in mode_records.items():
        hit_seq = [1.0 if v else 0.0 for v in rec.hits_by_event.values() if v is not None]
        lo, mid, hi = bootstrap_ci(hit_seq) if hit_seq else (0.0, 0.0, 0.0)
        avg_ret = sum(rec.returns) / len(rec.returns) if rec.returns else 0.0
        print(
            f"{label:<10}{rec.n_traded:>10}{rec.n_holds:>10}"
            f"{mid:>12.3f}{avg_ret:>14.4f}"
            f"  [{lo:.3f}, {hi:.3f}]"
        )


def _print_pairwise_mcnemar(mode_records: dict[str, _ModeRecords]) -> None:
    labels = list(mode_records.keys())
    print()
    print("=== McNemar paired tests (paired by event_id) ===")
    for i, a in enumerate(labels):
        for b in labels[i + 1:]:
            rec_a, rec_b = mode_records[a], mode_records[b]
            common = sorted(set(rec_a.hits_by_event) & set(rec_b.hits_by_event))
            a_hits = [rec_a.hits_by_event[e] for e in common]
            b_hits = [rec_b.hits_by_event[e] for e in common]
            result = mcnemar_paired(a_hits, b_hits)
            print(
                f"{a} vs {b}: n_paired={len(common)} "
                f"b={result.b} c={result.c} p={result.p_value:.4f} → {result.interpretation}"
            )


if __name__ == "__main__":
    main()
