"""LLM/DB 의존성 없이 backtest 컴포넌트 동작 확인.

run_pipeline은 실제 LLM 호출이 필요하므로 여기서는 검증하지 않는다.
그래프 호출 통합 테스트는 별도 환경(LangSmith + 모델 키)에서 수행.
"""
from datetime import date
from pathlib import Path

from app.backtest.clock import SimulatedClock
from app.backtest.portfolio_sim import PortfolioSim
from app.backtest.scorer import score_jsonl
from app.backtest.signal_replay import MockSignalSource


def test_clock_skips_weekends():
    clock = SimulatedClock(start=date(2025, 1, 4), end=date(2025, 1, 7))
    # 2025-01-04(토) → 월요일로 advance
    assert clock.current_date == date(2025, 1, 6)
    clock.tick()
    assert clock.current_date == date(2025, 1, 7)
    clock.tick()
    assert clock.is_done()


def test_mock_signal_source_is_deterministic():
    src = MockSignalSource(fire_probability=1.0)
    events_1 = src.signals_for(date(2025, 3, 15), ["005930"])
    events_2 = src.signals_for(date(2025, 3, 15), ["005930"])
    assert len(events_1) == 1
    assert events_1[0].stock_code == events_2[0].stock_code
    assert events_1[0].trigger.rule_ids == events_2[0].trigger.rule_ids


def test_portfolio_sim_buy_then_sell():
    pf = PortfolioSim(initial_cash=1_000_000)
    buy = pf.apply_decision("005930", "buy", order_amount=500_000, execution_price=50_000)
    assert buy["executed"] is True
    snap = pf.snapshot({"005930": 55_000})
    assert snap["cash_balance"] == 500_000
    assert len(snap["holdings"]) == 1
    holding = snap["holdings"][0]
    assert holding["stock_code"] == "005930"
    assert holding["average_price"] == 50_000
    assert holding["current_price"] == 55_000
    sell = pf.apply_decision("005930", "sell", order_amount=300_000, execution_price=55_000)
    assert sell["executed"] is True
    assert pf.snapshot()["cash_balance"] > 500_000  # 매도 수익 반영


def test_portfolio_sim_hold_is_noop():
    pf = PortfolioSim(initial_cash=1_000_000)
    result = pf.apply_decision("005930", "hold", order_amount=0, execution_price=50_000)
    assert result["executed"] is False
    assert pf.snapshot()["cash_balance"] == 1_000_000


class _FixedPriceFetcher:
    def __init__(self, price: float) -> None:
        self.price = price

    def close_price(self, stock_code: str, target_date: date) -> float:
        return self.price


def test_scorer_buy_profit(tmp_path: Path):
    jsonl = tmp_path / "out.jsonl"
    jsonl.write_text(
        '{"date":"2025-03-10","stock_code":"005930","action":"trade",'
        '"side":"buy","execution_price":50000}\n',
        encoding="utf-8",
    )
    result = score_jsonl(jsonl, price_fetcher=_FixedPriceFetcher(55_000), holding_days=7)
    assert result.traded == 1
    assert result.hits == 1
    assert abs(result.avg_return - 0.1) < 1e-6


def test_scorer_skips_hold(tmp_path: Path):
    jsonl = tmp_path / "out.jsonl"
    jsonl.write_text(
        '{"date":"2025-03-10","stock_code":"005930","action":"hold","side":null}\n',
        encoding="utf-8",
    )
    result = score_jsonl(jsonl, price_fetcher=_FixedPriceFetcher(55_000), holding_days=7)
    assert result.holds == 1
    assert result.traded == 0
