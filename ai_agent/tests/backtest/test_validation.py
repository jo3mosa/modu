"""Validation 컴포넌트(stats, random adapter) LLM/DB 의존성 없이 검증.

LangGraph adapter는 LLM 호출하므로 여기서 미테스트. 통합 테스트는 별도 환경.
"""
from datetime import date

from ai_agent.backtest.adapters.random_decision import make_random_decision_fn
from ai_agent.backtest.interfaces import Trigger
from ai_agent.backtest.stats import bootstrap_ci, mcnemar_paired


def _make_trigger(stock_code: str = "005930") -> Trigger:
    return Trigger(
        as_of_date=date(2025, 3, 15),
        stock_code=stock_code,
        rule_ids=["RSI-002"],
        rule_reasons=["RSI 과매수"],
        technical=None,
        fundamental=None,
        event=None,
        sentiment=None,
        close_price=70_000.0,
    )


def test_random_provider_is_reproducible():
    fn1 = make_random_decision_fn(seed=42)
    fn2 = make_random_decision_fn(seed=42)
    trig = _make_trigger()
    d1 = fn1(trig, {}, {})
    d2 = fn2(trig, {}, {})
    assert d1.action == d2.action
    assert d1.order_amount == d2.order_amount


def test_random_provider_does_not_call_llm():
    """LLM 키 없는 환경에서도 동작해야 한다."""
    fn = make_random_decision_fn(seed=1)
    for _ in range(20):
        decision = fn(_make_trigger(), {}, {})
        assert decision is not None
        assert decision.action in ("buy", "sell", "hold")


def test_random_provider_respects_weights():
    """weights=(1, 0, 0)이면 항상 buy."""
    fn = make_random_decision_fn(seed=1, weights=(1.0, 0.0, 0.0))
    for _ in range(10):
        d = fn(_make_trigger(), {}, {})
        assert d.action == "buy"


def test_mcnemar_identical_modes_have_p1():
    hits = [True, True, False, True, False]
    result = mcnemar_paired(hits, hits)
    assert result.b == 0 and result.c == 0
    assert result.p_value == 1.0


def test_mcnemar_extreme_difference_low_p():
    # mode_a가 10건 모두 hit, mode_b는 모두 miss → 강한 비대칭
    a = [True] * 10
    b = [False] * 10
    result = mcnemar_paired(a, b)
    assert result.b == 10 and result.c == 0
    assert result.p_value < 0.01


def test_mcnemar_rejects_length_mismatch():
    import pytest
    with pytest.raises(ValueError):
        mcnemar_paired([True, False], [True, False, True])


def test_bootstrap_ci_contains_mean():
    values = [1.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0, 1.0, 0.0]
    lo, mid, hi = bootstrap_ci(values, iterations=500, seed=1)
    assert lo <= mid <= hi
    assert abs(mid - 0.6) < 1e-9
    # CI 폭이 합리적: 0~1 사이
    assert 0.0 <= lo and hi <= 1.0


def test_bootstrap_ci_handles_empty():
    lo, mid, hi = bootstrap_ci([], iterations=100)
    assert (lo, mid, hi) == (0.0, 0.0, 0.0)
