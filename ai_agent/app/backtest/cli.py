"""Backtest CLI 진입점.

사용 예:
    python -m app.backtest.cli \\
        --user-id 1 --stocks 005930,000660 \\
        --start 2025-01-02 --end 2025-12-30 \\
        --output runs/backtest_2025.jsonl

memory_store 미주입 모드(--no-memory): retrieval 누적 없이 콜드 스타트만 검증.
실서비스 DB에 연결할 때는 DATABASE_URL 환경변수 + --use-memory.
"""
import argparse
import logging
from datetime import date
from pathlib import Path

from app.backtest.baselines import GraphDecisionProvider, RandomDecisionProvider
from app.backtest.replay_runner import BacktestConfig, run_backtest, StubPriceFetcher
from app.backtest.scorer import score_jsonl


def _parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="MODU backtest replay runner")
    p.add_argument("--user-id", type=int, required=True)
    p.add_argument("--stocks", type=str, required=True, help="콤마 구분 종목 코드")
    p.add_argument("--start", type=str, required=True, help="YYYY-MM-DD")
    p.add_argument("--end", type=str, required=True, help="YYYY-MM-DD")
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--initial-cash", type=int, default=10_000_000)
    p.add_argument(
        "--use-memory",
        action="store_true",
        help="DBMemoryStore에 결정 저장. 미설정 시 결정 저장 없음(retrieval 누적 비활성)",
    )
    p.add_argument(
        "--score-after",
        action="store_true",
        help="실행 후 scorer로 hit rate 출력",
    )
    p.add_argument(
        "--holding-days",
        type=int,
        default=7,
        help="채점 시 T+N일 (기본 7)",
    )
    p.add_argument(
        "--mode",
        type=str,
        choices=["A", "B", "random"],
        default="A",
        help="A=Bull/Bear 토론(기본), B=단일 에이전트(ablation), random=LLM 미호출 baseline",
    )
    p.add_argument(
        "--random-seed",
        type=int,
        default=42,
        help="--mode random일 때 결정 재현용 seed",
    )
    return p.parse_args()


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    args = _parse_args()

    config = BacktestConfig(
        user_id=args.user_id,
        stock_codes=[s.strip() for s in args.stocks.split(",") if s.strip()],
        start=date.fromisoformat(args.start),
        end=date.fromisoformat(args.end),
        output_path=args.output,
        initial_cash=args.initial_cash,
    )

    memory_store = _build_memory_store() if args.use_memory else None

    if args.mode == "random":
        provider = RandomDecisionProvider(seed=args.random_seed)
    else:
        provider = GraphDecisionProvider(mode=args.mode)

    run_backtest(
        config=config,
        memory_store=memory_store,
        decision_provider=provider,
    )

    if args.score_after:
        result = score_jsonl(args.output, price_fetcher=StubPriceFetcher(), holding_days=args.holding_days)
        print(f"total={result.total} traded={result.traded} holds={result.holds} skipped={result.skipped}")
        print(f"hit_rate={result.hit_rate:.3f} avg_return={result.avg_return:.4f}")
        for quarter, stats in sorted(result.by_quarter.items()):
            print(f"  {quarter}: n={stats['n']} hit={stats['hit_rate']:.3f} ret={stats['avg_return']:.4f}")


def _build_memory_store():
    from app.context.user_context import create_engine_from_env
    from app.memory.db_store import DBMemoryStore
    return DBMemoryStore(create_engine_from_env())


if __name__ == "__main__":
    main()
