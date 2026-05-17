"""DA backtest output JSONL 후처리 — raw_return + post_mortem_agent 호출.

DA framework가 출력한 JSONL을 입력으로 받아:
  1. 각 거래 결정에 raw_return을 계산 (T+N일 종가 기반)
  2. 결정 시점 추론(bull/bear/rationale)을 묶어 post_mortem_agent 호출
  3. 회고 텍스트 + 메트릭을 부착한 scored JSONL 출력

scored JSONL은 dashboard / LLM-as-Judge 평가의 입력이 된다.

DA framework 자체는 PnL/회고를 만들지 않는다 — AI 팀 책임. 이 모듈이 그 layer.
"""
from __future__ import annotations

import json
import logging
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Any, Callable, Protocol

logger = logging.getLogger(__name__)


class PriceFetcher(Protocol):
    """결정 후 N일 종가 + OHLC 4종 조회.

    close_price: 단일 종가 (기존 단순 raw_return 계산용)
    ohlc:        OHLC dict (target/stop 시뮬레이션용 — high/low로 도달 판정)
                 None 반환 시 휴장 또는 데이터 없음 — 해당 일 skip.
    """

    def close_price(self, stock_code: str, target_date: date) -> float: ...

    def ohlc(self, stock_code: str, target_date: date) -> dict[str, float] | None: ...


@dataclass
class ExitResult:
    """target/stop 시뮬 결과."""
    exit_date: date
    exit_price: float
    exit_reason: str   # "target_hit" / "stop_hit" / "expired" (N일 강제 청산)
    holding_days: int  # 실제 보유 거래일 수


def simulate_target_stop_exit(
    *,
    entry_date: date,
    entry_price: float,
    target_price: float | None,
    stop_loss_price: float | None,
    side: str,                       # "buy" 또는 "sell"
    max_holding_days: int,
    price_fetcher: PriceFetcher,
    stock_code: str,
) -> ExitResult | None:
    """매수 후 매일 OHLC 봐서 target/stop 도달 판정.

    BUY:
      그 날 high >= target → 익절 (target 가격)
      그 날 low <= stop    → 손절 (stop 가격)
      같은 날 둘 다 도달 시 보수적으로 stop 우선 (worst case)
      N일 끝 → 강제 종가 청산

    SELL (보유 청산 가정):
      그 날 low <= target → 익절 (target 가격 — 가격 하락이 익절)
      그 날 high >= stop  → 손절 (stop 가격 — 가격 상승이 손절)
      같은 날 둘 다 → 보수적으로 stop 우선

    target/stop 없으면 max_holding_days 후 종가 청산.
    OHLC 조회 실패 시 다음 거래일로 진행 (휴장 대비).
    """
    cur = entry_date
    for _ in range(max_holding_days):
        cur = _add_business_days(cur, 1)
        ohlc = price_fetcher.ohlc(stock_code, cur)
        if ohlc is None:
            continue   # 데이터 없는 날은 skip

        if side == "buy":
            stop_hit = stop_loss_price is not None and ohlc["low"] <= stop_loss_price
            target_hit = target_price is not None and ohlc["high"] >= target_price
            if stop_hit:
                return ExitResult(cur, float(stop_loss_price), "stop_hit",
                                  _business_days_between(entry_date, cur))
            if target_hit:
                return ExitResult(cur, float(target_price), "target_hit",
                                  _business_days_between(entry_date, cur))
        elif side == "sell":
            stop_hit = stop_loss_price is not None and ohlc["high"] >= stop_loss_price
            target_hit = target_price is not None and ohlc["low"] <= target_price
            if stop_hit:
                return ExitResult(cur, float(stop_loss_price), "stop_hit",
                                  _business_days_between(entry_date, cur))
            if target_hit:
                return ExitResult(cur, float(target_price), "target_hit",
                                  _business_days_between(entry_date, cur))

    # N일 끝 강제 청산 — 마지막 cur의 종가
    final_ohlc = price_fetcher.ohlc(stock_code, cur)
    if final_ohlc is None:
        # 마지막 날도 휴장이면 직전 영업일로 fallback
        return None
    return ExitResult(cur, float(final_ohlc["close"]), "expired",
                      _business_days_between(entry_date, cur))


def _business_days_between(start: date, end: date) -> int:
    """start ~ end 영업일 개수 (start 제외, end 포함)."""
    if end <= start:
        return 0
    cur, n = start, 0
    while cur < end:
        cur = cur + timedelta(days=1)
        if cur.weekday() < 5:
            n += 1
    return n


@dataclass
class ScoreResult:
    total: int
    traded: int
    hits: int
    losses: int
    holds: int
    skipped: int
    hit_rate: float
    avg_return: float
    by_quarter: dict[str, dict[str, float]]


# ============================================================
# 1. 단순 hit_rate / avg_return 집계 (post_mortem 없음)
# ============================================================


def score_jsonl(
    input_path: Path,
    price_fetcher: PriceFetcher,
    holding_days: int = 7,
) -> ScoreResult:
    """결정 기록 JSONL을 채점. BUY/SELL 방향 hit + 분기별 분해."""
    total = traded = hits = losses = holds = skipped = 0
    returns: list[float] = []
    by_quarter: dict[str, list[float]] = defaultdict(list)

    for record in _iter_records(input_path):
        total += 1
        rec = _normalize_record(record)
        action, side = rec.get("action"), rec.get("side")

        if action == "hold" or side not in ("buy", "sell"):
            holds += 1
            continue

        entry, trade_date = rec.get("execution_price"), _parse_date(rec.get("date"))
        if not entry or trade_date is None:
            skipped += 1
            continue

        exit_date = _add_business_days(trade_date, holding_days)
        try:
            exit_price = price_fetcher.close_price(rec["stock_code"], exit_date)
        except Exception:
            logger.exception("scoring: 종가 조회 실패 %s %s", rec.get("stock_code"), exit_date)
            skipped += 1
            continue

        if not exit_price or entry <= 0:
            skipped += 1
            continue

        ret = (exit_price - entry) / entry
        if side == "sell":
            ret = -ret
        traded += 1
        returns.append(ret)
        if ret > 0:
            hits += 1
        else:
            losses += 1
        by_quarter[_quarter_key(trade_date)].append(ret)

    return ScoreResult(
        total=total, traded=traded, hits=hits, losses=losses, holds=holds, skipped=skipped,
        hit_rate=(hits / traded) if traded else 0.0,
        avg_return=(sum(returns) / len(returns)) if returns else 0.0,
        by_quarter={
            q: {
                "n": len(rs),
                "hit_rate": sum(1 for r in rs if r > 0) / len(rs) if rs else 0.0,
                "avg_return": sum(rs) / len(rs) if rs else 0.0,
            }
            for q, rs in by_quarter.items()
        },
    )


# ============================================================
# 2. Post-mortem 통합 — scored JSONL 생성
# ============================================================


def score_with_post_mortem(
    input_path: Path,
    output_path: Path,
    price_fetcher: PriceFetcher,
    holding_days: int = 7,
    post_mortem_fn: Callable[..., Any] | None = None,
    benchmark_fetcher: Any | None = None,
) -> None:
    """JSONL 라인별로 raw_return 계산 + post_mortem_agent 호출 → scored JSONL.

    post_mortem_fn 미주입 시 app.agents.feedback.post_mortem_agent를 실 LLM 호출.
    benchmark_fetcher (KospiBenchmarkFetcher 등) 주입 시 alpha_return 산출.
    """
    pm_fn = post_mortem_fn or _default_post_mortem
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8") as f_out:
        for raw_rec in _iter_records(input_path):
            rec = _normalize_record(raw_rec)
            scored = _score_one(rec, price_fetcher, holding_days, pm_fn, benchmark_fetcher)
            f_out.write(json.dumps(scored, ensure_ascii=False, default=str) + "\n")


def _default_post_mortem(**kwargs):
    """import 지연 — backtest 모듈 로딩 시 LangChain 비용 없음."""
    from app.agents.feedback.post_mortem_agent import post_mortem_agent
    return post_mortem_agent(**kwargs)


def _score_one(
    rec: dict[str, Any],
    price_fetcher: PriceFetcher,
    holding_days: int,
    pm_fn: Callable[..., Any],
    benchmark_fetcher: Any | None = None,
) -> dict[str, Any]:
    """단일 결정 레코드에 target/stop 시뮬 + raw_return + post_mortem 부착.

    이전: T+N일 종가 단순 비교.
    현재: 일별 OHLC를 봐서 target/stop 도달 시 그 가격에 청산. 진짜 PnL.
    """
    action, side = rec.get("action"), rec.get("side")
    base = {**rec, "raw_return": None, "alpha_return": None, "exit_price": None,
            "exit_date": None, "exit_reason": None,
            "post_mortem": None, "skip_reason": None}

    if action == "hold" or side not in ("buy", "sell"):
        base["skip_reason"] = "hold or non-directional"
        return base

    entry, trade_date = rec.get("execution_price"), _parse_date(rec.get("date"))
    if not entry or trade_date is None:
        base["skip_reason"] = "missing entry price or date"
        return base

    # target/stop 시뮬
    try:
        exit_result = simulate_target_stop_exit(
            entry_date=trade_date,
            entry_price=float(entry),
            target_price=float(rec["target_price"]) if rec.get("target_price") else None,
            stop_loss_price=float(rec["stop_loss_price"]) if rec.get("stop_loss_price") else None,
            side=side,
            max_holding_days=holding_days,
            price_fetcher=price_fetcher,
            stock_code=rec["stock_code"],
        )
    except Exception:
        logger.exception("scoring: target/stop 시뮬 실패 %s %s",
                         rec.get("stock_code"), trade_date)
        base["skip_reason"] = "target/stop simulation failed"
        return base

    if exit_result is None:
        base["skip_reason"] = "exit price fetch failed"
        return base

    raw_return = (exit_result.exit_price - entry) / entry
    if side == "sell":
        raw_return = -raw_return

    # KOSPI 벤치마크 대비 alpha — benchmark_fetcher 주입된 경우만 산출.
    alpha_return = _compute_alpha_return(
        raw_return=raw_return,
        entry_date=trade_date,
        exit_date=exit_result.exit_date,
        benchmark_fetcher=benchmark_fetcher,
    )

    reflection_dump = None
    try:
        reflection = pm_fn(
            decision_content=_compose_decision_content(rec),
            raw_return=raw_return,
            alpha_return=alpha_return if alpha_return is not None else 0.0,
            holding_days=exit_result.holding_days,
            risk_level=None,
            key_signals=rec.get("rule_ids"),
        )
        if reflection is not None:
            reflection_dump = (
                reflection.model_dump() if hasattr(reflection, "model_dump") else dict(reflection)
            )
    except Exception:
        logger.exception("scoring: post_mortem 호출 실패 (계속 진행)")

    return {
        **rec,
        "exit_price": exit_result.exit_price,
        "exit_date": exit_result.exit_date.isoformat(),
        "exit_reason": exit_result.exit_reason,
        "raw_return": raw_return,
        "alpha_return": alpha_return,
        "holding_days": exit_result.holding_days,
        "post_mortem": reflection_dump,
        "skip_reason": None,
    }


def _compute_alpha_return(
    *, raw_return: float, entry_date: date, exit_date: date,
    benchmark_fetcher: Any | None,
) -> float | None:
    """전략 수익률 - 같은 기간 KOSPI 수익률. benchmark 없거나 가격 누락 시 None.

    benchmark_fetcher.close_price(_, date) 시그니처를 사용 — KospiBenchmarkFetcher 호환.
    """
    if benchmark_fetcher is None:
        return None
    try:
        entry_p = float(benchmark_fetcher.close_price("KOSPI", entry_date))
        exit_p = float(benchmark_fetcher.close_price("KOSPI", exit_date))
    except Exception:
        logger.exception("alpha 계산 실패 — benchmark 가격 조회")
        return None
    if entry_p <= 0 or exit_p <= 0:
        return None
    bench_return = (exit_p - entry_p) / entry_p
    return raw_return - bench_return


def _compose_decision_content(rec: dict[str, Any]) -> str:
    """post_mortem_agent에 넘길 결정 시점 추론 텍스트."""
    parts: list[str] = []
    if rec.get("judgment_reason"):
        parts.append(f"[판단 사유]\n{rec['judgment_reason']}")
    if rec.get("bull_claim"):
        parts.append(f"[Bull 주장]\n{rec['bull_claim']}")
    if rec.get("bear_claim"):
        parts.append(f"[Bear 주장]\n{rec['bear_claim']}")
    if rec.get("winning_side"):
        parts.append(f"[우세 관점]\n{rec['winning_side']}")
    return "\n\n".join(parts) if parts else "(판단 사유 없음)"


# ============================================================
# DA framework JSONL 포맷 정규화
# ============================================================


def _normalize_record(rec: dict[str, Any]) -> dict[str, Any]:
    """DA framework output을 우리 scorer가 기대하는 평탄한 dict로 변환.

    DA 포맷                  → 정규화 키
    ────────────────────────────────────────
    as_of_date              → date
    decision.action(buy/sell/hold) → action(trade/hold) + side(buy/sell)
    decision.order_amount   → order_amount
    decision.target_price   → target_price
    decision.stop_loss_price→ stop_loss_price
    decision.confidence     → confidence
    decision.reasoning      → judgment_reason
    decision.extras.bull/bear/winning_side → bull_claim/bear_claim/winning_side
    fill.fill_price (or close_price fallback) → execution_price
    rule_ids                → rule_ids 그대로
    """
    # 이미 우리 포맷이면 그대로
    if "date" in rec and ("action" in rec or "side" in rec):
        return rec

    out: dict[str, Any] = dict(rec)
    out["date"] = rec.get("as_of_date")
    out["stock_code"] = rec.get("stock_code")
    out["rule_ids"] = rec.get("rule_ids") or []

    decision = rec.get("decision") or {}
    if isinstance(decision, dict):
        da_action = decision.get("action")
        out["action"] = "hold" if da_action == "hold" else "trade"
        out["side"] = da_action if da_action in ("buy", "sell") else None
        out["order_amount"] = decision.get("order_amount")
        out["target_price"] = decision.get("target_price")
        out["stop_loss_price"] = decision.get("stop_loss_price")
        out["confidence"] = decision.get("confidence")
        out["judgment_reason"] = decision.get("reasoning")
        extras = decision.get("extras") or {}
        if isinstance(extras, dict):
            out["winning_side"] = extras.get("winning_side")
            out["bull_claim"] = extras.get("bull_claim")
            out["bear_claim"] = extras.get("bear_claim")

    fill = rec.get("fill") or {}
    fill_price = fill.get("fill_price") if isinstance(fill, dict) else None
    out["execution_price"] = fill_price if fill_price is not None else rec.get("close_price")

    return out


# ============================================================
# 내부 헬퍼
# ============================================================


def _iter_records(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                yield json.loads(line)
            except json.JSONDecodeError:
                logger.warning("JSONL 파싱 실패, 라인 스킵")


def _parse_date(value: Any) -> date | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(value).date() if "T" in str(value) else date.fromisoformat(value)
    except ValueError:
        return None


def _add_business_days(d: date, n: int) -> date:
    cur = d
    remaining = n
    while remaining > 0:
        cur += timedelta(days=1)
        if cur.weekday() < 5:
            remaining -= 1
    return cur


def _quarter_key(d: date) -> str:
    return f"{d.year}-Q{((d.month - 1) // 3) + 1}"
