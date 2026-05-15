"""Scorer.

replay_runner가 출력한 JSONL을 읽어 결정별 T+N일 수익률을 계산한다.

다음 PR 작업:
- PriceFetcher.close_price를 실제 analysis_server DB의 historical OHLCV 조회로 교체
- 분기별/모드별(A/B/C) 분해 + LangSmith metadata 매칭

이번 PR(스켈레톤)에서는 인터페이스 + 단순 hit rate 계산까지 제공.
"""
import json
import logging
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Any, Callable, Protocol

logger = logging.getLogger(__name__)


class PriceFetcher(Protocol):
    """결정 후 N일 종가 조회. replay_runner.PriceFetcher와 동일 시그니처를 공유한다."""

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


def score_jsonl(
    input_path: Path,
    price_fetcher: PriceFetcher,
    holding_days: int = 7,
) -> ScoreResult:
    """결정 기록 JSONL을 채점한다.

    BUY: (T+N가 - T가) / T가
    SELL: (T가 - T+N가) / T가   (단순화: 평단 무시)
    HOLD: 채점 제외
    """
    total = 0
    traded = 0
    hits = 0
    losses = 0
    holds = 0
    skipped = 0
    returns: list[float] = []
    by_quarter: dict[str, list[float]] = defaultdict(list)

    for record in _iter_records(input_path):
        total += 1
        action = record.get("action")
        side = record.get("side")

        if action == "hold" or side not in ("buy", "sell"):
            holds += 1
            continue

        entry_price = record.get("execution_price")
        if not entry_price:
            skipped += 1
            continue

        trade_date = _parse_date(record.get("date"))
        if trade_date is None:
            skipped += 1
            continue

        exit_date = _add_business_days(trade_date, holding_days)
        try:
            exit_price = price_fetcher.close_price(record["stock_code"], exit_date)
        except Exception:
            logger.exception("종가 조회 실패: %s %s", record.get("stock_code"), exit_date)
            skipped += 1
            continue

        if not exit_price or entry_price <= 0:
            skipped += 1
            continue

        ret = (exit_price - entry_price) / entry_price
        if side == "sell":
            ret = -ret

        traded += 1
        returns.append(ret)
        if ret > 0:
            hits += 1
        else:
            losses += 1

        quarter = _quarter_key(trade_date)
        by_quarter[quarter].append(ret)

    hit_rate = (hits / traded) if traded else 0.0
    avg_return = (sum(returns) / len(returns)) if returns else 0.0

    return ScoreResult(
        total=total,
        traded=traded,
        hits=hits,
        losses=losses,
        holds=holds,
        skipped=skipped,
        hit_rate=hit_rate,
        avg_return=avg_return,
        by_quarter={
            q: {
                "n": len(rs),
                "hit_rate": sum(1 for r in rs if r > 0) / len(rs) if rs else 0.0,
                "avg_return": sum(rs) / len(rs) if rs else 0.0,
            }
            for q, rs in by_quarter.items()
        },
    )


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


# ============================================================
# Post-Mortem 통합 채점기
# ============================================================
# 평가 framework "5️⃣ 추론 품질 — 사후 추론"의 입력 데이터를 만드는 단계.
# replay_runner JSONL → 각 거래에 raw_return 계산 + post_mortem_agent 호출 → scored JSONL.
# scored JSONL은 LLM-as-Judge가 reasoning_score로 평가한다.


def score_with_post_mortem(
    input_path: Path,
    output_path: Path,
    price_fetcher: PriceFetcher,
    holding_days: int = 7,
    post_mortem_fn: Callable[..., Any] | None = None,
) -> None:
    """JSONL 라인별로 raw_return 계산 + post_mortem 호출 → scored JSONL.

    post_mortem_fn:
        주입 안 하면 app.agents.feedback.post_mortem_agent.post_mortem_agent 사용 (실 LLM 호출).
        테스트/실험에서 모킹할 때 주입.

    alpha_return:
        MVP — 0.0으로 단순화. 벤치마크(KOSPI 등) 수익률 차감 미구현.
        TODO: KOSPI 일별 종가 fetch + 동기간 수익률 차감 (별도 PR).
    """
    pm_fn = post_mortem_fn or _default_post_mortem
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8") as f_out:
        for rec in _iter_records(input_path):
            scored = _score_one(rec, price_fetcher, holding_days, pm_fn)
            f_out.write(json.dumps(scored, ensure_ascii=False, default=str) + "\n")


def _default_post_mortem(**kwargs):
    """실 LLM 호출 경로. import는 함수 안에서 해 backtest 모듈 로딩 시 비용 0."""
    from app.agents.feedback.post_mortem_agent import post_mortem_agent
    return post_mortem_agent(**kwargs)


def _score_one(
    rec: dict[str, Any],
    price_fetcher: PriceFetcher,
    holding_days: int,
    pm_fn: Callable[..., Any],
) -> dict[str, Any]:
    """단일 결정 레코드에 raw_return + 청산가 + post_mortem reflection 부착."""
    action = rec.get("action")
    side = rec.get("side")

    base = {**rec, "raw_return": None, "alpha_return": None, "exit_price": None,
            "post_mortem": None, "skip_reason": None}

    if action == "hold" or side not in ("buy", "sell"):
        base["skip_reason"] = "hold or non-directional"
        return base

    entry = rec.get("execution_price")
    trade_date = _parse_date(rec.get("date"))
    if not entry or trade_date is None:
        base["skip_reason"] = "missing entry price or date"
        return base

    exit_date = _add_business_days(trade_date, holding_days)
    try:
        exit_price = price_fetcher.close_price(rec["stock_code"], exit_date)
    except Exception:
        logger.exception("scorer: 종가 조회 실패 %s %s", rec.get("stock_code"), exit_date)
        base["skip_reason"] = "exit price fetch failed"
        return base

    if not exit_price or entry <= 0:
        base["skip_reason"] = "invalid exit price"
        return base

    raw_return = (exit_price - entry) / entry
    if side == "sell":
        raw_return = -raw_return

    decision_content = _compose_decision_content(rec)
    reflection_dump = None
    try:
        reflection = pm_fn(
            decision_content=decision_content,
            raw_return=raw_return,
            alpha_return=0.0,  # TODO: KOSPI 벤치마크 차감
            holding_days=holding_days,
            risk_level=None,
            key_signals=rec.get("rule_ids"),
        )
        if reflection is not None:
            reflection_dump = (
                reflection.model_dump() if hasattr(reflection, "model_dump") else dict(reflection)
            )
    except Exception:
        logger.exception("scorer: post_mortem 호출 실패 (계속 진행)")

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
    """post_mortem_agent에 넘길 결정 시점 추론 텍스트.

    feedback/pipeline.py:_load_decision_context의 포맷과 동일하게 맞춤.
    """
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
