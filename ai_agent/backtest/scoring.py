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
    """결정 후 N일 종가 조회. DA data_sources.fetch_ohlcv_by_date 결과로 구현 가능."""

    def close_price(self, stock_code: str, target_date: date) -> float: ...


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
        (hits if ret > 0 else losses).__add__(0)  # type: ignore
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
) -> None:
    """JSONL 라인별로 raw_return 계산 + post_mortem_agent 호출 → scored JSONL.

    post_mortem_fn 미주입 시 app.agents.feedback.post_mortem_agent를 실 LLM 호출.
    alpha_return = 0.0 (TODO: KOSPI 벤치마크 차감).
    """
    pm_fn = post_mortem_fn or _default_post_mortem
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8") as f_out:
        for raw_rec in _iter_records(input_path):
            rec = _normalize_record(raw_rec)
            scored = _score_one(rec, price_fetcher, holding_days, pm_fn)
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
) -> dict[str, Any]:
    """단일 결정 레코드에 raw_return + 청산가 + post_mortem reflection 부착."""
    action, side = rec.get("action"), rec.get("side")
    base = {**rec, "raw_return": None, "alpha_return": None, "exit_price": None,
            "post_mortem": None, "skip_reason": None}

    if action == "hold" or side not in ("buy", "sell"):
        base["skip_reason"] = "hold or non-directional"
        return base

    entry, trade_date = rec.get("execution_price"), _parse_date(rec.get("date"))
    if not entry or trade_date is None:
        base["skip_reason"] = "missing entry price or date"
        return base

    exit_date = _add_business_days(trade_date, holding_days)
    try:
        exit_price = price_fetcher.close_price(rec["stock_code"], exit_date)
    except Exception:
        logger.exception("scoring: 종가 조회 실패 %s %s", rec.get("stock_code"), exit_date)
        base["skip_reason"] = "exit price fetch failed"
        return base

    if not exit_price or entry <= 0:
        base["skip_reason"] = "invalid exit price"
        return base

    raw_return = (exit_price - entry) / entry
    if side == "sell":
        raw_return = -raw_return

    reflection_dump = None
    try:
        reflection = pm_fn(
            decision_content=_compose_decision_content(rec),
            raw_return=raw_return,
            alpha_return=0.0,
            holding_days=holding_days,
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
        "exit_price": exit_price,
        "raw_return": raw_return,
        "alpha_return": 0.0,
        "holding_days": holding_days,
        "post_mortem": reflection_dump,
        "skip_reason": None,
    }


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
