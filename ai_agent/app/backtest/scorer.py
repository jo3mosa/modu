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
from typing import Any, Protocol

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
