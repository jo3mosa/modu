"""AI팀 진입점 — DA event_loop를 LangGraph(or random) 어댑터로 실행.

DA의 run_backtest.py는 mock_decision으로 인프라 검증만. 이 모듈은 실 LangGraph
의사결정 + 후처리(scoring + post_mortem)까지 통합.

사용:
    # LLM 미호출 — 인프라/데이터 점검 (Postgres + Mongo 만 필요)
    python -m ai_agent.backtest.run_ai_backtest \\
        --mode random --start 2024-01-02 --end 2024-01-05 \\
        --watchlist 005930,000660,035720 --output backtest_out/random

    # 실 LangGraph (mode A — Bull/Bear 토론, LLM 호출됨)
    python -m ai_agent.backtest.run_ai_backtest \\
        --mode A --start 2024-01-02 --end 2024-01-03 \\
        --watchlist 005930 --output backtest_out/mode_A

    # 위 + scoring + post_mortem 후처리 (LLM 추가 호출. --pm-mock으로 회피 가능)
    python -m ai_agent.backtest.run_ai_backtest \\
        --mode A --start 2024-01-02 --end 2024-01-03 \\
        --output backtest_out/mode_A --score-after --pm-mock

--mode 옵션:
    random — LLM 미호출. baseline용. cost = 0
    A       — LangGraph (Bull/Bear → Strategy → Decision). MVP.
    B       — LangGraph (Strategy → Decision, 단일 에이전트 ablation)
    mock    — DA의 simple_rule_decision (룰 패턴 매칭, LLM 미호출. 비교용)
"""
from __future__ import annotations

import argparse
import logging
import os
import sys
from datetime import date, datetime
from pathlib import Path
from urllib.parse import quote_plus

from dotenv import load_dotenv
from sqlalchemy.engine import Engine

from . import config
from .data_sources import fetch_ohlcv_by_date, make_engine
from .event_loop import run
from .examples.mock_decision import (
    SimplePortfolio,
    flat_user_context,
    simple_rule_decision,
)
from .interfaces import DecisionFn

logger = logging.getLogger(__name__)


# ============================================================
# decision_fn 선택 — mode → callable
# ============================================================


def _build_decision_fn(mode: str) -> DecisionFn:
    """--mode 인자를 실제 decision_fn으로 변환.

    각 모드의 LLM 호출 횟수:
        random — 0 (랜덤 dict 생성)
        mock   — 0 (단순 룰 패턴 매칭)
        A      — 4회/결정 (context + bull + bear + strategy + decision; context/risk_gate는 LLM 없음)
        B      — 2회/결정 (context + strategy + decision)
    """
    if mode == "random":
        from .adapters.random_decision import make_random_decision_fn
        return make_random_decision_fn(seed=42)
    if mode == "mock":
        return simple_rule_decision
    if mode in ("A", "B"):
        from .adapters.graph_decision import make_graph_decision_fn
        return make_graph_decision_fn(mode=mode)
    raise ValueError(f"unknown mode: {mode}")


# ============================================================
# 후처리 — DA JSONL → scored JSONL (raw_return + post_mortem)
# ============================================================


class _OhlcvPriceFetcher:
    """DA data_sources를 활용한 PriceFetcher 어댑터.

    close_price: 단일 종가 (기존)
    ohlc:        4종 OHLC dict (target/stop 시뮬용)
    """

    def __init__(self, engine: Engine) -> None:
        self._engine = engine

    def close_price(self, stock_code: str, target_date: date) -> float:
        rows = fetch_ohlcv_by_date(self._engine, target_date)
        row = rows.get(stock_code)
        if not row:
            return 0.0
        close = row.get("close")
        return float(close) if close is not None else 0.0

    def ohlc(self, stock_code: str, target_date: date) -> dict[str, float] | None:
        """{open, high, low, close} 또는 데이터 없으면 None."""
        rows = fetch_ohlcv_by_date(self._engine, target_date)
        row = rows.get(stock_code)
        if not row:
            return None
        try:
            return {
                "open": float(row["open"]),
                "high": float(row["high"]),
                "low": float(row["low"]),
                "close": float(row["close"]),
            }
        except (KeyError, TypeError, ValueError):
            return None


def _score_after_run(
    *,
    engine: Engine,
    output_root: Path,
    holding_days: int,
    pm_mock: bool,
) -> None:
    """DA가 출력한 triggers_*.jsonl을 읽어 scored_*.jsonl을 만든다.

    pm_mock=True 면 LLM 호출 없이 fake_post_mortem 사용 (인프라 검증).
    pm_mock=False 면 실 post_mortem_agent (LLM 호출, 추가 비용).
    """
    from .scoring import score_with_post_mortem
    pm_fn = None
    if pm_mock:
        from .examples.generate_dummy_jsonl import fake_post_mortem
        pm_fn = fake_post_mortem

    price = _OhlcvPriceFetcher(engine)
    files = sorted(output_root.glob("triggers_*.jsonl"))
    if not files:
        logger.warning("score-after: triggers_*.jsonl 파일 없음 — %s", output_root)
        return

    for f in files:
        out = f.parent / f.name.replace("triggers_", "scored_")
        score_with_post_mortem(f, out, price_fetcher=price, holding_days=holding_days, post_mortem_fn=pm_fn)
    logger.info("score-after: %d 파일 scored 생성", len(files))


# ============================================================
# CLI
# ============================================================


def _parse_date(s: str) -> date:
    return datetime.strptime(s, "%Y-%m-%d").date()


def _maybe_compose_database_url() -> None:
    """DATABASE_URL이 비어 있으면 DB_HOST/PORT/NAME/USERNAME/PASSWORD에서 합성.

    팀 .env 컨벤션이 분리된 변수라 DA framework의 단일 URL 요구와 다름.
    합성 후 os.environ에 set — DA config.load_env()가 그대로 본다.
    """
    if os.getenv("DATABASE_URL"):
        return
    host = os.getenv("DB_HOST")
    name = os.getenv("DB_NAME")
    if not host or not name:
        return  # 합성 재료 부족 — 원래 에러 그대로 전파
    port = os.getenv("DB_PORT", "5432")
    user = os.getenv("DB_USERNAME", "")
    password = os.getenv("DB_PASSWORD", "")
    auth = ""
    if user:
        auth = quote_plus(user)
        if password:
            auth += ":" + quote_plus(password)
        auth += "@"
    url = f"postgresql+psycopg2://{auth}{host}:{port}/{name}"
    os.environ["DATABASE_URL"] = url
    logger.info("DATABASE_URL을 DB_* 변수로부터 합성: postgresql+psycopg2://%s%s:%s/%s",
                "***@" if auth else "", host, port, name)


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )
    # DA config는 repo 루트 .env만 보지만 우리 팀은 보통 ai_agent/.env에 둔다.
    # 둘 다 시도 — 먼저 로드한 쪽이 우선되지 않도록 override=True 사용.
    here = Path(__file__).resolve()
    for candidate in (here.parents[2] / ".env", here.parents[1] / ".env"):
        if candidate.exists():
            load_dotenv(candidate, override=True)
            logger.info(".env loaded: %s", candidate)

    # DATABASE_URL이 비어 있고 분리된 DB_* 변수가 있으면 자동 합성.
    # DA framework는 DATABASE_URL 통합 형식만 받으므로 변환 필요.
    _maybe_compose_database_url()

    parser = argparse.ArgumentParser(description="AI 통합 backtest (LangGraph + post_mortem)")
    parser.add_argument("--mode", required=True, choices=["random", "mock", "A", "B"],
                        help="decision_fn 선택")
    parser.add_argument("--start", default=config.DEFAULT_START_DATE.isoformat())
    parser.add_argument("--end", default=config.DEFAULT_END_DATE.isoformat())
    parser.add_argument("--user-id", default="backtest-user")
    parser.add_argument("--watchlist", default=None,
                        help="쉼표 구분 종목. 미지정 시 활성 종목 자동")
    parser.add_argument("--initial-cash", type=float, default=10_000_000,
                        help="가상 포트폴리오 초기 현금 (KRW). 기본 1천만원")
    parser.add_argument("--initial-holdings", type=str, default=None,
                        help="시작 시 보유 종목 (콤마구분 'CODE:QTY'). "
                             "예: 005930:100,000660:50. SELL 결정 정상 체결에 필요")
    parser.add_argument("--output", type=Path,
                        default=Path(__file__).resolve().parent / "runs" / "default",
                        help="기본: ai_agent/backtest/runs/default — 모드/날짜로 디렉터리 분리 권장")
    parser.add_argument("--score-after", action="store_true",
                        help="run() 종료 후 score_with_post_mortem 자동 호출")
    parser.add_argument("--pm-mock", action="store_true",
                        help="--score-after에 fake post_mortem 사용 (LLM 미호출)")
    parser.add_argument("--holding-days", type=int, default=7,
                        help="--score-after에서 T+N일 종가 채점")
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

    # initial_holdings 파싱: "005930:100,000660:50"
    initial_holdings: dict[str, int] | None = None
    if args.initial_holdings:
        try:
            initial_holdings = {
                code.strip(): int(qty.strip())
                for part in args.initial_holdings.split(",")
                for code, qty in [part.split(":", 1)]
                if code.strip() and qty.strip()
            }
        except (ValueError, IndexError):
            print(f"[ERROR] --initial-holdings 형식 오류: {args.initial_holdings}",
                  file=sys.stderr)
            return 1

    logger.info("=== AI backtest 시작 (mode=%s, 초기 자금=%s KRW, 초기 보유=%s) ===",
                args.mode, f"{args.initial_cash:,.0f}",
                initial_holdings or "없음")
    decision_fn = _build_decision_fn(args.mode)
    portfolio = SimplePortfolio(
        user_id=args.user_id,
        initial_cash_krw=args.initial_cash,
        initial_holdings=initial_holdings,
    )

    result = run(
        env=env,
        start=_parse_date(args.start),
        end=_parse_date(args.end),
        output_root=args.output,
        decision_fn=decision_fn,
        user_context_fn=flat_user_context,
        portfolio=portfolio,
        user_id=args.user_id,
        watchlist_override=watchlist,
    )
    print(f"run_id={result['run_id']}  summary={result['summary_path']}")

    # event_loop 종료 후 SimplePortfolio.equity_curve를 JSONL로 export
    # dashboard PnL 탭이 일별 자산 추이로 활용
    _export_equity_curve(portfolio, args.output)

    if args.score_after:
        engine = make_engine(env)
        _score_after_run(
            engine=engine,
            output_root=args.output,
            holding_days=args.holding_days,
            pm_mock=args.pm_mock,
        )

    return 0


def _export_equity_curve(portfolio, output_root: Path) -> None:
    """SimplePortfolio.equity_curve (일별 mark-to-market 결과)를 equity_curve.jsonl로 출력.

    각 라인: {date, cash, unrealized, equity, holdings_count}
    dashboard의 자산 추이 차트가 이 데이터를 사용.
    """
    import json
    curve = getattr(portfolio, "equity_curve", None) or []
    if not curve:
        logger.info("equity_curve 비어있음 — export skip")
        return
    output_root.mkdir(parents=True, exist_ok=True)
    out = output_root / "equity_curve.jsonl"
    with out.open("w", encoding="utf-8") as f:
        for snap in curve:
            f.write(json.dumps(snap, ensure_ascii=False, default=str) + "\n")
    logger.info("equity_curve export: %d 일치 → %s", len(curve), out)


if __name__ == "__main__":
    raise SystemExit(main())
