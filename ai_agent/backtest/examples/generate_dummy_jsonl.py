"""dummy backtest JSONL 생성기 — Postgres/Mongo/LLM 없이 동작 검증.

DA framework의 output.build_record와 동일한 형식으로 가짜 결정 N건을 만들어
runs/dummy/triggers_*.jsonl에 기록한다.

사용:
    python -m ai_agent.backtest.examples.generate_dummy_jsonl \\
        --output runs/dummy --days 20 --stocks 005930,000660 --seed 42

이후 scoring으로 후처리 + dashboard에서 시각화:
    python -m ai_agent.backtest.examples.generate_dummy_jsonl --output runs/dummy
    python -c "
    from pathlib import Path
    from ai_agent.backtest.scoring import score_with_post_mortem
    from ai_agent.backtest.examples.generate_dummy_jsonl import DummyPriceFetcher, fake_post_mortem
    for f in sorted(Path('runs/dummy').glob('triggers_*.jsonl')):
        score_with_post_mortem(
            f, f.parent / f.name.replace('triggers_', 'scored_'),
            price_fetcher=DummyPriceFetcher(seed=42),
            post_mortem_fn=fake_post_mortem,
        )
    "
    streamlit run ai_agent/dashboards/backtest_viewer.py
"""
from __future__ import annotations

import argparse
import json
import random
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from hashlib import md5
from pathlib import Path

_RULE_POOL = [
    ("RSI-002", "RSI 과매수", "sell"),
    ("RSI-001", "RSI 과매도", "buy"),
    ("MACD-001", "MACD 골든크로스", "buy"),
    ("VOL-001", "거래량 급증", "buy"),
    ("BB-002", "볼린저 상단 돌파", "sell"),
    ("DART-001", "공시 악재", "sell"),
    ("SENT-001", "긍정 뉴스 우세", "buy"),
]


def _next_business_day(d: date) -> date:
    nxt = d + timedelta(days=1)
    while nxt.weekday() >= 5:
        nxt += timedelta(days=1)
    return nxt


def _hash_int(*parts: str) -> int:
    return int(md5("|".join(parts).encode()).hexdigest()[:8], 16)


def _dummy_record(
    *, run_id: str, user_id: str, day: date, stock_code: str, seed_offset: int
) -> dict:
    rng = random.Random(_hash_int(stock_code, day.isoformat(), str(seed_offset)))
    rule_id, reason, suggested_side = rng.choice(_RULE_POOL)
    close_price = float(rng.randint(30_000, 120_000))

    # 60% trade / 40% hold (대시보드에 HOLD 분포 보이게)
    do_trade = rng.random() < 0.6
    if do_trade:
        side = suggested_side if rng.random() < 0.7 else rng.choice(["buy", "sell"])
        action = side
        order_amount = rng.randint(100_000, 500_000) // int(close_price) * int(close_price)
        target_offset = 0.04 + rng.random() * 0.04
        stop_offset = 0.02 + rng.random() * 0.02
        target = close_price * (1 + target_offset) if side == "buy" else close_price * (1 - target_offset)
        stop = close_price * (1 - stop_offset) if side == "buy" else close_price * (1 + stop_offset)
        decision = {
            "action": side,
            "order_amount": int(order_amount),
            "target_price": round(target, 0),
            "stop_loss_price": round(stop, 0),
            "confidence": round(0.5 + rng.random() * 0.4, 3),
            "reasoning": f"dummy: {reason} → {side}",
            "extras": {
                "winning_side": "BULL" if side == "buy" else "BEAR",
                "bull_claim": f"{reason}로 인한 상승 기대" if side == "buy" else "단기 반등 가능",
                "bear_claim": f"{reason}로 인한 하락 우려" if side == "sell" else "추세 약화 신호",
            },
        }
        # 체결가에 ±0.5% 노이즈
        fill_price = close_price * (1 + (rng.random() - 0.5) * 0.01)
        fill = {
            "fill_date": _next_business_day(day).isoformat(),
            "fill_price": round(fill_price, 0),
            "filled_amount": int(order_amount // close_price) or 1,
            "fee": round(fill_price * 0.00015, 2),
            "tax": round(fill_price * 0.0018, 2) if side == "sell" else 0.0,
            "notes": "dummy_next_day_open",
        }
    else:
        decision = {
            "action": "hold",
            "order_amount": None,
            "target_price": None,
            "stop_loss_price": None,
            "confidence": round(0.3 + rng.random() * 0.3, 3),
            "reasoning": "dummy: 신호 약함",
            "extras": {},
        }
        fill = None

    return {
        "run_id": run_id,
        "user_id": user_id,
        "as_of_date": day.isoformat(),
        "stock_code": stock_code,
        "rule_ids": [rule_id],
        "rule_reasons": [reason],
        "close_price": close_price,
        "signals": {
            "technical": {"rsi": 25 + rng.random() * 50},
            "fundamental": {},
            "event": {"has_urgent_issue": rule_id.startswith("DART")},
            "sentiment": {"daily_score": round((rng.random() - 0.5) * 2, 3)},
        },
        "decision": decision,
        "fill": fill,
        "user_context": {"user_id": user_id, "risk_profile": "moderate"},
        "portfolio_snapshot": {
            "user_id": user_id,
            "cash": 10_000_000 - seed_offset * 50_000,
            "holdings": {},
        },
        "extras": {},
        "recorded_at": datetime.now(timezone.utc).isoformat(),
    }


# ============================================================
# scoring 후처리용 dummy helpers
# ============================================================


@dataclass
class DummyPriceFetcher:
    """결정 시점 close + ±5% 노이즈 = exit_price. seed로 재현 가능."""
    seed: int = 42
    base_prices: dict[str, float] | None = None

    def __post_init__(self):
        self._rng = random.Random(self.seed)
        self._cache: dict[tuple[str, str], float] = {}

    def close_price(self, stock_code: str, target_date: date) -> float:
        key = (stock_code, target_date.isoformat())
        if key in self._cache:
            return self._cache[key]
        base = (self.base_prices or {}).get(stock_code) or _hash_int(stock_code) % 80_000 + 30_000
        # 결정론적 변동 (날짜 해시)
        delta = (_hash_int(stock_code, target_date.isoformat()) % 200 - 100) / 1000.0  # ±10%
        price = base * (1 + delta)
        self._cache[key] = price
        return price

    def ohlc(self, stock_code: str, target_date: date) -> dict | None:
        """결정론적 OHLC — close 주변에 ±2% 폭으로 high/low를 만든다.

        scoring.simulate_target_stop_exit이 일별 high/low를 봐서 target/stop
        도달을 판정하므로 dummy 경로에서도 ohlc()가 필요.
        """
        close = self.close_price(stock_code, target_date)
        if close <= 0:
            return None
        # 같은 시드에서 동일 출력 — date+code 해시로 spread 폭 결정.
        spread_bps = (_hash_int(stock_code, target_date.isoformat(), "spread") % 400) / 10000.0
        high = close * (1 + spread_bps)
        low = close * (1 - spread_bps)
        return {"open": close, "high": high, "low": low, "close": close}


def fake_post_mortem(**kwargs):
    """LLM 미호출 가짜 회고. scoring 후처리 흐름 검증용."""
    raw_return = kwargs.get("raw_return", 0.0)
    success = raw_return > 0
    return {
        "entry_timing_assessment": "양호" if success else "조기 진입",
        "exit_rule_assessment": "목표 도달" if success else "stop 발동 직전",
        "risk_prediction_accuracy": "정확" if success else "리스크 과소평가",
        "missed_signals": [] if success else ["변동성 신호"],
        "lessons": ["target까지 보유"] if success else ["손절 라인 재검토"],
        "summary": f"dummy: raw_return={raw_return:.2%}, 성공" if success else f"dummy: raw_return={raw_return:.2%}, 실패",
    }


# ============================================================
# CLI
# ============================================================


def main() -> None:
    parser = argparse.ArgumentParser(description="dummy backtest JSONL 생성기")
    parser.add_argument("--output", type=Path,
                        default=Path(__file__).resolve().parent.parent / "dummy",
                        help="기본: ai_agent/backtest/dummy/")
    parser.add_argument("--days", type=int, default=20)
    parser.add_argument("--stocks", type=str, default="005930,000660,035720")
    parser.add_argument("--start", type=str, default="2025-01-02")
    parser.add_argument("--user-id", type=str, default="backtest-user")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--mode", type=str, default="debate_1")
    args = parser.parse_args()

    args.output.mkdir(parents=True, exist_ok=True)
    stocks = [s.strip() for s in args.stocks.split(",") if s.strip()]
    start = datetime.strptime(args.start, "%Y-%m-%d").date()

    run_id = f"dummy_{start.isoformat()}_{args.user_id}"
    cur = start
    total = 0
    for i in range(args.days):
        path = args.output / f"triggers_{cur.isoformat()}.jsonl"
        with path.open("w", encoding="utf-8") as f:
            for stock_code in stocks:
                rec = _dummy_record(
                    run_id=run_id, user_id=args.user_id,
                    day=cur, stock_code=stock_code, seed_offset=i,
                )
                rec["mode"] = args.mode  # dashboard 필터링 용도
                f.write(json.dumps(rec, ensure_ascii=False, default=str) + "\n")
                total += 1
        cur = _next_business_day(cur)

    print(f"생성 완료: {total}개 결정, {args.days}일치 → {args.output}")


if __name__ == "__main__":
    main()
