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

import json
import logging
import time
import uuid
from collections import Counter
from dataclasses import dataclass
from datetime import date, datetime
from pathlib import Path
from typing import Optional

from pymongo.errors import (
    AutoReconnect,
    ConnectionFailure,
    NetworkTimeout,
    ServerSelectionTimeoutError,
)
from sqlalchemy.engine import Engine

from . import data_sources, signal_generator
from .config import BacktestEnv, EVENT_LOOKBACK_DAYS, SENTIMENT_LOOKBACK_DAYS
from .interfaces import Decision, DecisionFn, Fill, PortfolioFn, SignalFn, UserContextFn
from .output import JsonlWriter, build_record, open_writer, write_summary

logger = logging.getLogger(__name__)


# LLM 호출 실패가 reasoning에 남기는 마커 — bull/bear/strategy_manager의 fallback 메시지.
# resume 시 영업일을 partial로 마킹할지 판단에 사용.
_LLM_FAIL_MARKERS = ("LLM 호출 실패", "LLM 출력 파싱", "재시도 중 LLM")


# Mongo 원격 단절 시 dashboard(backtest_viewer)가 로그에서 감지하는 마커.
# 형식 변경 시 dashboards/backtest_viewer.py의 _mongo_status_from_log도 함께 갱신할 것.
_MONGO_DISCONNECT_MARKER = "[MONGO_DISCONNECTED]"
_MONGO_WAITING_MARKER = "[MONGO_WAITING]"
_MONGO_RECONNECT_MARKER = "[MONGO_RECONNECTED]"


def _dump_equity_curve_partial(portfolio: "PortfolioFn", output_root: Path) -> None:
    """매 영업일 EOD에 equity_curve.jsonl을 partial dump한다.

    Anthropic harness 원칙의 "구조화된 핸드오프" 적용 — 백테스트 종료를 기다리지
    않고 매 영업일 결과를 즉시 파일로 노출해, dashboard viewer가 진행 중에도
    자산 추이 / 정통 메트릭 / KOSPI alpha 카드를 30초 주기로 갱신할 수 있게 한다.

    portfolio가 equity_curve 속성을 노출하지 않거나(다른 PortfolioFn 구현체) 빈 경우
    silent skip — 머니패스 영향 없음.
    영업일당 ~수십 KB 이내 작은 파일이라 rewrite 비용 무시 가능.
    """
    curve = getattr(portfolio, "equity_curve", None) or []
    if not curve:
        return
    try:
        output_root.mkdir(parents=True, exist_ok=True)
        path = output_root / "equity_curve.jsonl"
        with path.open("w", encoding="utf-8") as f:
            for snap in curve:
                f.write(json.dumps(snap, ensure_ascii=False, default=str) + "\n")
    except Exception:
        logger.exception("equity_curve partial dump 실패 — 진행은 계속")

# 무한 재시도 간격(초). pymongo의 socketTimeoutMS(60s)·retryReads(1회)로 흡수 안 된
# 장기 단절을 영업일 단위에서 직접 처리. Postgres 등 다른 예외는 catch하지 않음.
_MONGO_RETRY_INTERVAL_SEC = 30


def _run_one_day_with_mongo_retry(
    *,
    engine: Engine,
    mongo,
    day: date,
    watchlist_override: Optional[list[str]],
    signal_fn: Optional["SignalFn"] = None,
) -> tuple[list, dict]:
    """_run_one_day를 감싸 Mongo 원격 단절 시 무한 재시도한다.

    Mongo가 다시 살아나면 죽었던 영업일을 처음부터 다시 실행한다 — 즉
    "Mongo 죽기 직전 영업일부터 다시 진행"이 자동으로 보장됨.

    재시도 대상 예외 (모두 pymongo의 connection-level 장애):
      - AutoReconnect / ConnectionFailure / NetworkTimeout / ServerSelectionTimeoutError

    Postgres 예외나 LLM 예외 등 다른 장애는 의도적으로 catch하지 않는다.
    Ctrl+C(KeyboardInterrupt)는 sleep을 즉시 중단해 정상 종료된다.

    콘솔에 dashboard polling이 감지하는 마커를 출력해 Streamlit UI가
    "끊김 / 대기 중 / 재연결" 상태를 표시할 수 있게 한다.
    """
    attempt = 0
    started = time.monotonic()
    while True:
        try:
            result = _run_one_day(
                engine=engine, mongo=mongo, day=day,
                watchlist_override=watchlist_override,
                signal_fn=signal_fn,
            )
        except (AutoReconnect, ConnectionFailure,
                NetworkTimeout, ServerSelectionTimeoutError) as exc:
            attempt += 1
            elapsed = int(time.monotonic() - started)
            if attempt == 1:
                logger.warning(
                    "%s day=%s reason=%s",
                    _MONGO_DISCONNECT_MARKER, day, type(exc).__name__,
                )
                logger.warning(
                    "MongoDB 원격 연결 끊김 — 영업일 %s 재연결 대기 중 "
                    "(%ds 간격 무한 재시도, Ctrl+C로 중단)",
                    day, _MONGO_RETRY_INTERVAL_SEC,
                )
            else:
                logger.warning(
                    "%s day=%s attempt=%d elapsed=%ds",
                    _MONGO_WAITING_MARKER, day, attempt, elapsed,
                )
            time.sleep(_MONGO_RETRY_INTERVAL_SEC)
            continue

        if attempt > 0:
            waited = int(time.monotonic() - started)
            logger.warning(
                "%s day=%s waited=%ds",
                _MONGO_RECONNECT_MARKER, day, waited,
            )
            logger.warning(
                "MongoDB 재연결 성공 — 영업일 %s 처음부터 재시작 완료 (대기 ~%.1f분)",
                day, waited / 60,
            )
        return result


def _is_llm_failed(decision: Optional[Decision]) -> bool:
    """decision.reasoning에 LLM 실패 마커가 있으면 True."""
    if decision is None:
        return False
    reasoning = (getattr(decision, "reasoning", None) or "")
    return any(marker in reasoning for marker in _LLM_FAIL_MARKERS)


def _day_sentinel_paths(output_root: Path, day: date) -> tuple[Path, Path, Path]:
    """영업일별 (triggers jsonl, .done 마커, .partial 마커) 경로 트리플."""
    return (
        output_root / f"triggers_{day.isoformat()}.jsonl",
        output_root / f"triggers_{day.isoformat()}.done",
        output_root / f"triggers_{day.isoformat()}.partial",
    )


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
    settled_fills: int = 0       # 다음 사이클 settle()로 실제 적용된 pending 건수
    settled_buys: int = 0
    settled_sells: int = 0
    # LLM 토큰/비용 누적 — graph_decision adapter가 Decision.extras에 부착하면 누적.
    # random/mock 모드는 LLM 미호출이라 0 유지.
    prompt_tokens: int = 0
    completion_tokens: int = 0
    total_tokens: int = 0
    estimated_cost_usd: float = 0.0
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
            "settled_fills": self.settled_fills,
            "settled_buys": self.settled_buys,
            "settled_sells": self.settled_sells,
            "prompt_tokens": self.prompt_tokens,
            "completion_tokens": self.completion_tokens,
            "total_tokens": self.total_tokens,
            "estimated_cost_usd": round(self.estimated_cost_usd, 6),
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
    resume: bool = False,
    mode: Optional[str] = None,
) -> dict:
    """백테스트 1회 실행. 통계 dict 반환.

    watchlist_override 가 주어지면 매일 그 종목들만 평가 — 디버깅·소규모 실험용.
    None 이면 매일 daily_ohlcv 의 거래 기록으로 watchlist 자동 산출 (생존편향 회피).

    signal_fn:
        None 이면 signal_generator.detect_all() 사용 (기본 지표 룰 트리거).
        주입 시 해당 함수로 트리거 생성을 대체 (llm_trigger 모드 등).

    resume:
        True면 영업일 루프 시작에서 triggers_{day}.done 파일이 있는 날은 skip.
        partial 또는 부분 jsonl만 있는 날은 jsonl 삭제 후 재처리. portfolio 자체의
        복원은 호출자(run_ai_backtest)가 SimplePortfolio.from_dict로 처리 후 주입.
        영업일 EOD에 portfolio_{day}.json + .done(또는 .partial) sentinel 작성.
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

            # resume 모드: 이미 정상 완료된 영업일은 skip, partial/부분 jsonl은 재처리.
            triggers_jsonl, done_marker, partial_marker = _day_sentinel_paths(output_root, day)
            if resume:
                if done_marker.exists():
                    logger.info("[%d/%d] %s — resume skip (이미 .done)",
                                i, len(trading_days), day)
                    continue
                # partial 또는 jsonl만 있고 done 없으면 부분 실행된 영업일 → 삭제 후 재처리
                if partial_marker.exists() or triggers_jsonl.exists():
                    triggers_jsonl.unlink(missing_ok=True)
                    partial_marker.unlink(missing_ok=True)
                    logger.info("[%d/%d] %s — resume retry (partial 정리 완료)",
                                i, len(trading_days), day)

            # 이 영업일에서 LLM 호출 실패가 한 번이라도 발생했는지 — EOD 마킹 판단에 사용.
            fallback_detected = False

            # 이전 사이클 execute()가 적재한 pending_orders를 오늘 실제 적용.
            # cash/holdings/open_positions는 이 시점에 갱신 → 이후 단계의
            # decision_fn snapshot / evaluate_open_positions / mark_to_market은
            # 모두 settle 직후 상태를 본다 (T+1 진입을 T 데이터로 평가하던
            # 시점 꼬임 해결의 핵심).
            if hasattr(portfolio, "settle"):
                try:
                    settled = portfolio.settle(day)
                    for sf in settled or []:
                        stats.settled_fills += 1
                        if sf.notes and "buy" in sf.notes:
                            stats.settled_buys += 1
                        elif sf.notes and "sell" in sf.notes:
                            stats.settled_sells += 1
                except Exception:
                    logger.exception("portfolio.settle 실패 day=%s", day)

            triggers, fetched_stocks = _run_one_day_with_mongo_retry(
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

                    # LLM 호출 실패 fallback 감지 — 영업일을 partial로 마킹해
                    # resume 시 재처리하도록.
                    if _is_llm_failed(decision):
                        fallback_detected = True

                    # 토큰/비용 누적 — graph_decision adapter가 Decision.extras에 부착.
                    # random/mock은 extras에 토큰 키 없음 → 0 누적 (no-op).
                    extras = getattr(decision, "extras", None) or {}
                    stats.prompt_tokens += int(extras.get("prompt_tokens") or 0)
                    stats.completion_tokens += int(extras.get("completion_tokens") or 0)
                    stats.total_tokens += int(extras.get("total_tokens") or 0)
                    stats.estimated_cost_usd += float(extras.get("estimated_cost_usd") or 0.0)

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
                    mode=mode,
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
                        mode=mode,
                    ))

            # EOD: mark-to-market — close_prices 는 그날 종가.
            close_prices = {sc: row.get("close")
                            for sc, row in fetched_stocks.items()}
            try:
                portfolio.mark_to_market(day, close_prices)
            except Exception:
                logger.exception("portfolio.mark_to_market 실패 day=%s", day)

            # 진행 중 viewer 핸드오프 — 매 영업일 EOD에 equity_curve를 즉시 파일로 dump.
            # 백테스트 종료를 기다리지 않고 부분 결과(자산 추이 / 정통 메트릭 / KOSPI alpha)를
            # streamlit dashboard가 실시간으로 표시할 수 있게 한다.
            _dump_equity_curve_partial(portfolio, output_root)

            # resume용 portfolio 상태 dump + 영업일 sentinel 마킹.
            # portfolio가 to_dict를 구현한 경우(SimplePortfolio)만 dump.
            if hasattr(portfolio, "to_dict"):
                try:
                    (output_root / f"portfolio_{day.isoformat()}.json").write_text(
                        json.dumps(portfolio.to_dict(), ensure_ascii=False, default=str),
                        encoding="utf-8",
                    )
                except Exception:
                    logger.exception("portfolio dump 실패 day=%s", day)
            try:
                sentinel = partial_marker if fallback_detected else done_marker
                sentinel.touch()
            except Exception:
                logger.exception("영업일 sentinel 마킹 실패 day=%s", day)

            writer.flush()
            elapsed = time.monotonic() - day_started
            logger.info("[%d/%d] %s — triggers=%d / fills=%d / %.1fs",
                        i, len(trading_days), day,
                        len(triggers), stats.fills, elapsed)

    ended_at = datetime.utcnow()
    # 마지막 일자에 남은 pending — 다음 영업일이 백테스트 범위 밖이라 settle 불가.
    # record(JSONL)에는 이미 예약 Fill이 발행되어 있어 결정/scoring 흐름은 정상.
    # 단, 그 결정은 실제 자산 곡선에 반영되지 못함 → 경고로 가시화.
    unsettled = len(getattr(portfolio, "pending_orders", []) or [])
    if unsettled:
        logger.warning("백테스트 종료 시 미체결 pending %d건 — 마지막 일자 결정은 "
                       "다음 영업일 settle 기회가 없어 자산 곡선 미반영", unsettled)
    # equity_curve 기반 표준 메트릭 — portfolio가 노출하는 경우만 산출.
    equity_metrics = _compute_equity_metrics_if_available(portfolio)
    stats_payload = stats.as_dict()
    stats_payload["unsettled_pending_at_end"] = unsettled
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
            "mode": mode,
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
    mode: Optional[str] = None,
) -> dict:
    """stop_loss/target_price 자동 청산 Fill 전용 레코드.

    트리거가 없는 청산이므로 일반 trigger 레코드와 구분되는 record_type 부여.
    scoring/대시보드가 무시해도 동작이 깨지지 않도록 키 set은 보수적으로.
    """
    return {
        "run_id": run_id,
        "user_id": user_id,
        "mode": mode,
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
