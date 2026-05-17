"""백테스트 메인 루프 — 23-24년 영업일 시간 흐름대로 트리거·의사결정·체결.

흐름:
  for day in trading_days(start, end):
      1. watchlist 산출 (day 에 거래 기록 있는 종목 — survivorship 회피)
      2. bulk fetch: ohlcv / indicators / fundamentals / disclosures / news
      3. signal_generator.detect_all → triggers
      4. for trigger in triggers:
            user_ctx = user_context_fn(day, user_id)
            decision = decision_fn(trigger, user_ctx, portfolio.snapshot())
            if decision.action != "hold":
                fill = portfolio.execute(trigger, decision, next_day_market)
            output.write(day, record)
      5. EOD: portfolio.mark_to_market — 일별 자산 곡선

AI 팀 책임은 decision_fn / user_context_fn / portfolio 인스턴스 주입.
hold 결정은 체결 단계 skip — Decision.action == "hold" 가 명시 신호.
"""

from __future__ import annotations

import logging
import time
import uuid
from collections import Counter
from dataclasses import dataclass
from datetime import date, datetime
from pathlib import Path
from typing import Optional

from sqlalchemy.engine import Engine

from . import data_sources, signal_generator
from .config import BacktestEnv, EVENT_LOOKBACK_DAYS, SENTIMENT_LOOKBACK_DAYS
from .interfaces import DecisionFn, Fill, PortfolioFn, SignalFn, UserContextFn
from .output import JsonlWriter, build_record, open_writer, write_summary

logger = logging.getLogger(__name__)


@dataclass
class BacktestStats:
    """run() 종료 시 summary 에 들어가는 누적치."""
    days: int = 0
    triggers_total: int = 0
    triggers_with_decision: int = 0
    decision_errors: int = 0
    fills: int = 0
    stop_fills: int = 0          # evaluate_open_positions로 자동 청산된 건수
    target_fills: int = 0
    action_counter: Counter = None
    rule_counter: Counter = None

    def __post_init__(self):
        if self.action_counter is None:
            self.action_counter = Counter()
        if self.rule_counter is None:
            self.rule_counter = Counter()

    def as_dict(self) -> dict:
        return {
            "days": self.days,
            "triggers_total": self.triggers_total,
            "triggers_with_decision": self.triggers_with_decision,
            "decision_errors": self.decision_errors,
            "fills": self.fills,
            "stop_fills": self.stop_fills,
            "target_fills": self.target_fills,
            "action_distribution": dict(self.action_counter),
            "rule_distribution": dict(self.rule_counter),
        }


def run(
    *,
    env: BacktestEnv,
    start: date,
    end: date,
    output_root: Path,
    decision_fn: DecisionFn,
    user_context_fn: UserContextFn,
    portfolio: PortfolioFn,
    user_id: str = "backtest-user",
    watchlist_override: Optional[list[str]] = None,
    run_id: Optional[str] = None,
    signal_fn: Optional[SignalFn] = None,
) -> dict:
    """백테스트 1회 실행. 통계 dict 반환.

    watchlist_override 가 주어지면 매일 그 종목들만 평가 — 디버깅·소규모 실험용.
    None 이면 매일 daily_ohlcv 의 거래 기록으로 watchlist 자동 산출 (생존편향 회피).

    signal_fn:
        None 이면 signal_generator.detect_all() 사용 (기본 지표 룰 트리거).
        주입 시 해당 함수로 트리거 생성을 대체 (llm_trigger 모드 등).
    """
    run_id = run_id or _new_run_id(start, end, user_id)
    started_at = datetime.utcnow()
    stats = BacktestStats()

    engine = data_sources.make_engine(env)
    trading_days = data_sources.fetch_trading_days(engine, start, end)
    logger.info("백테스트 시작 run_id=%s — %d 영업일 (%s ~ %s)",
                run_id, len(trading_days), start, end)

    with open_writer(output_root) as writer, \
         data_sources.mongo_client(env) as mongo:

        for i, day in enumerate(trading_days, 1):
            day_started = time.monotonic()
            triggers, fetched_stocks = _run_one_day(
                engine=engine, mongo=mongo, day=day,
                watchlist_override=watchlist_override,
                signal_fn=signal_fn,
            )
            stats.days += 1
            stats.triggers_total += len(triggers)
            for trig in triggers:
                stats.rule_counter.update(trig.rule_ids)

            # 다음 영업일 OHLC bulk — 트리거 발생 종목 한정.
            next_day_market = _next_market(engine, day,
                                            [t.stock_code for t in triggers])

            # user_context_fn 은 일자당 1 회만 호출 — 트리거 수만큼 호출 시
            # 외부 DB hit 비용이 누적되고, 같은 일자 의사결정 간 user 상태가
            # 어긋날 수 있어 트리거 루프 밖에서 결정.
            user_ctx = _safe_user_context(user_context_fn, day, user_id)

            for trig in triggers:
                record_decision = None
                record_fill: Optional[Fill] = None
                snapshot = portfolio.snapshot()

                try:
                    decision = decision_fn(trig, user_ctx, snapshot)
                    record_decision = decision
                    stats.triggers_with_decision += 1
                    stats.action_counter[decision.action] += 1

                    if decision.action != "hold":
                        market = next_day_market.get(trig.stock_code, {})
                        try:
                            record_fill = portfolio.execute(trig, decision, market)
                            if record_fill is not None:
                                stats.fills += 1
                        except Exception:
                            logger.exception("portfolio.execute 실패 stock=%s",
                                             trig.stock_code)
                except Exception:
                    logger.exception("decision_fn 실패 stock=%s rule_ids=%s",
                                     trig.stock_code, trig.rule_ids)
                    stats.decision_errors += 1

                writer.write(day, build_record(
                    run_id=run_id, user_id=user_id, trigger=trig,
                    decision=record_decision, fill=record_fill,
                    user_context=user_ctx, portfolio_snapshot=snapshot,
                ))

            # 보유 포지션의 stop_loss / target_price 도달 평가 (선택 구현).
            # mark_to_market 직전에 실행 — 도달 시 청산이 EOD 자산에 반영되도록.
            if hasattr(portfolio, "evaluate_open_positions"):
                try:
                    auto_fills = portfolio.evaluate_open_positions(day, fetched_stocks)
                except Exception:
                    logger.exception("evaluate_open_positions 실패 day=%s", day)
                    auto_fills = []
                for af in auto_fills or []:
                    if af.notes and af.notes.startswith("stop_loss_hit"):
                        stats.stop_fills += 1
                    elif af.notes and af.notes.startswith("target_hit"):
                        stats.target_fills += 1
                    stats.fills += 1
                    writer.write(day, _build_auto_fill_record(
                        run_id=run_id, user_id=user_id, day=day, fill=af,
                        portfolio_snapshot=portfolio.snapshot(),
                    ))

            # EOD: mark-to-market — close_prices 는 그날 종가.
            close_prices = {sc: row.get("close")
                            for sc, row in fetched_stocks.items()}
            try:
                portfolio.mark_to_market(day, close_prices)
            except Exception:
                logger.exception("portfolio.mark_to_market 실패 day=%s", day)

            writer.flush()
            elapsed = time.monotonic() - day_started
            logger.info("[%d/%d] %s — triggers=%d / fills=%d / %.1fs",
                        i, len(trading_days), day,
                        len(triggers), stats.fills, elapsed)

    ended_at = datetime.utcnow()
    # equity_curve 기반 표준 메트릭 — portfolio가 노출하는 경우만 산출.
    equity_metrics = _compute_equity_metrics_if_available(portfolio)
    stats_payload = stats.as_dict()
    if equity_metrics is not None:
        stats_payload["equity_metrics"] = equity_metrics
        logger.info("equity 메트릭 — total_return=%.2f%% / CAGR=%.2f%% / "
                    "Sharpe=%.2f / MaxDD=%.2f%%",
                    equity_metrics["total_return_pct"] * 100,
                    equity_metrics["cagr"] * 100,
                    equity_metrics["sharpe"],
                    equity_metrics["max_drawdown_pct"] * 100)
    summary_path = write_summary(
        output_root, run_id=run_id,
        started_at=started_at, ended_at=ended_at,
        config_dict={
            "start": start.isoformat(),
            "end": end.isoformat(),
            "user_id": user_id,
            "watchlist_override": watchlist_override,
            "event_lookback_days": EVENT_LOOKBACK_DAYS,
            "sentiment_lookback_days": SENTIMENT_LOOKBACK_DAYS,
        },
        stats=stats_payload,
    )
    logger.info("백테스트 완료 — summary %s", summary_path)
    return {"run_id": run_id, "summary_path": str(summary_path),
            "stats": stats_payload}


def _compute_equity_metrics_if_available(portfolio: PortfolioFn) -> Optional[dict]:
    """portfolio가 equity_curve 속성을 노출하면 메트릭 산출, 아니면 None."""
    curve = getattr(portfolio, "equity_curve", None)
    if not curve:
        return None
    try:
        from .portfolio_metrics import compute_equity_metrics
        return compute_equity_metrics(curve)
    except Exception:
        logger.exception("equity 메트릭 산출 실패")
        return None


def _build_auto_fill_record(
    *, run_id: str, user_id: str, day, fill: Fill,
    portfolio_snapshot,
) -> dict:
    """stop_loss/target_price 자동 청산 Fill 전용 레코드.

    트리거가 없는 청산이므로 일반 trigger 레코드와 구분되는 record_type 부여.
    scoring/대시보드가 무시해도 동작이 깨지지 않도록 키 set은 보수적으로.
    """
    return {
        "run_id": run_id,
        "user_id": user_id,
        "as_of_date": day,
        "record_type": "auto_fill",
        "stock_code": fill.notes.split(":")[-1] if fill.notes and ":" in fill.notes else None,
        "fill": fill,
        "portfolio_snapshot": portfolio_snapshot,
        "recorded_at": datetime.utcnow().isoformat() + "Z",
    }


# ─── 내부 헬퍼 ───────────────────────────────────────────────────────────────

def _run_one_day(
    *,
    engine: Engine,
    mongo,
    day: date,
    watchlist_override: Optional[list[str]],
    signal_fn: Optional[SignalFn] = None,
) -> tuple[list, dict]:
    """하루치 bulk fetch + 트리거 검출. (triggers, ohlcv_by_stock) 반환.

    signal_fn 이 주어지면 signal_generator.detect_all() 대신 사용.
    """
    # None 만 자동 산출 트리거. 빈 리스트는 "명시적으로 0 종목" 의도이므로
    # 그대로 빈 watchlist 로 흘려보내 트리거 0 건으로 마감.
    if watchlist_override is None:
        watchlist = data_sources.fetch_watchlist_on(engine, day)
    else:
        watchlist = watchlist_override
    if not watchlist:
        return [], {}

    ohlcv = data_sources.fetch_ohlcv_by_date(engine, day)
    indicators = data_sources.fetch_indicators_by_date(engine, day, include_prev=True)
    fundamentals = data_sources.fetch_fundamentals_by_date(engine, day)
    disclosures = data_sources.fetch_disclosures_window(mongo, day, watchlist)
    news = data_sources.fetch_news_window(mongo, day, watchlist)

    _trigger_fn = signal_fn if signal_fn is not None else signal_generator.detect_all
    triggers = _trigger_fn(
        as_of=day, watchlist=watchlist,
        ohlcv_by_stock=ohlcv,
        indicators_by_stock=indicators,
        fundamentals_by_stock=fundamentals,
        disclosures_by_stock=disclosures,
        news_by_stock=news,
    )
    return triggers, ohlcv


def _next_market(engine: Engine, day: date, stock_codes: list[str]) -> dict:
    """체결 시뮬레이션용 — 다음 영업일 OHLC dict."""
    if not stock_codes:
        return {}
    return data_sources.fetch_next_open(engine, day, list(set(stock_codes)))


def _safe_user_context(fn: UserContextFn, day: date, user_id: str) -> dict:
    """user_context_fn 예외 격리 — 의사결정만 skip 되도록 caller 에서 catch
    하지만, 사용자 컨텍스트가 비어도 record 는 남기는 게 디버깅에 유용."""
    try:
        return fn(day, user_id)
    except Exception:
        logger.exception("user_context_fn 실패 day=%s user_id=%s", day, user_id)
        return {}


def _new_run_id(start: date, end: date, user_id: str) -> str:
    return f"{start.isoformat()}_{end.isoformat()}_{user_id}_{uuid.uuid4().hex[:8]}"
