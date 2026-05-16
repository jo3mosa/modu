"""백테스트 CLI 진입점.

기본 모드는 examples.mock_decision 의 stub 으로 인프라 동작 검증. AI 팀은
자체 의사결정 함수를 import 해서 run() 을 직접 호출하거나, 이 모듈에 새 CLI
옵션을 추가하면 됨.

사용법 (repo 루트에서, .env 가 DATABASE_URL · MONGO_URI 포함):
    python -m ai_agent.backtest.run_backtest --start 2023-01-02 --end 2023-01-31
    python -m ai_agent.backtest.run_backtest --watchlist 005930,000660
    python -m ai_agent.backtest.run_backtest --output ./backtest_out
"""

from __future__ import annotations

import argparse
import logging
import sys
from datetime import date, datetime
from pathlib import Path

from dotenv import load_dotenv

from . import config
from .event_loop import run
from .examples.mock_decision import build_mock_components


def _parse_date(s: str) -> date:
    return datetime.strptime(s, "%Y-%m-%d").date()


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    # repo 루트 .env 로드 — DATABASE_URL / MONGO_URI.
    # ai_agent/backtest/run_backtest.py → modu/.env
    env_path = Path(__file__).resolve().parents[2] / ".env"
    load_dotenv(env_path)

    parser = argparse.ArgumentParser(
        description="AI 에이전트 백테스트 (23-24년 데이터 기반)",
    )
    parser.add_argument("--start", default=config.DEFAULT_START_DATE.isoformat(),
                        help="시작일 YYYY-MM-DD")
    parser.add_argument("--end", default=config.DEFAULT_END_DATE.isoformat(),
                        help="종료일 YYYY-MM-DD")
    parser.add_argument("--user-id", default="backtest-user",
                        help="가상 사용자 ID — output 레코드 식별자")
    parser.add_argument("--watchlist", default=None,
                        help="쉼표 구분 종목 코드. 미지정 시 매일 활성 종목 자동")
    parser.add_argument("--output", default="./backtest_out",
                        help="JSONL + summary 출력 디렉터리")
    args = parser.parse_args()

    try:
        env = config.load_env()
    except RuntimeError as e:
        print(f"[ERROR] {e}", file=sys.stderr)
        return 1

    watchlist = (
        [s.strip() for s in args.watchlist.split(",") if s.strip()]
        if args.watchlist else None
    )

    decision_fn, user_context_fn, portfolio = build_mock_components(args.user_id)

    result = run(
        env=env,
        start=_parse_date(args.start),
        end=_parse_date(args.end),
        output_root=Path(args.output),
        decision_fn=decision_fn,
        user_context_fn=user_context_fn,
        portfolio=portfolio,
        user_id=args.user_id,
        watchlist_override=watchlist,
    )
    print(f"run_id={result['run_id']}  summary={result['summary_path']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
