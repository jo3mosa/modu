"""Validation 컴포넌트(baselines, stats) LLM/DB 의존성 없이 검증.

GraphDecisionProvider는 LLM 호출하므로 여기서 미테스트. compare CLI 통합 테스트는 별도 환경.
"""
from app.backtest.baselines import RandomDecisionProvider
from app.backtest.stats import bootstrap_ci, mcnemar_paired
from app.triggers.schemas import MarketTrigger, UserTriggerEvent
from datetime import datetime


def _make_event(stock_code: str = "005930") -> UserTriggerEvent:
    return UserTriggerEvent(
        timestamp=datetime(2025, 3, 15, 9, 0),
        user_id=1,
        stock_code=stock_code,
        trigger=MarketTrigger(rule_ids=["RSI-002"]),
    )


def test_random_provider_is_reproducible():
    p1 = RandomDecisionProvider(seed=42)
    p2 = RandomDecisionProvider(seed=42)
    event = _make_event()
    r1 = p1.decide(event)
    r2 = p2.decide(event)
    assert r1["final_decision"].action == r2["final_decision"].action
    assert r1["final_decision"].side == r2["final_decision"].side


def test_random_provider_does_not_call_llm():
    """LLM 키 없는 환경에서도 동작해야 한다."""
    provider = RandomDecisionProvider(seed=1)
    for _ in range(20):
        result = provider.decide(_make_event())
        assert result["final_decision"] is not None
        assert result["flow_status"] == "completed"


def test_random_provider_respects_weights():
    """weights=(1, 0, 0)이면 항상 buy."""
    p = RandomDecisionProvider(seed=1, weights=(1.0, 0.0, 0.0))
    for _ in range(10):
        result = p.decide(_make_event())
        assert result["final_decision"].side == "buy"
        assert result["final_decision"].action == "trade"


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
