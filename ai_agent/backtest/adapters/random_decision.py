"""LLM 미호출 baseline — random decision adapter.

목적:
  그래프(Bull/Bear 또는 단일)가 random보다 통계적으로 나은지 검증.
  baseline 없으면 "토론 패턴 효과"가 trigger 자체 효과인지 그래프 효과인지 분리 불가.

사용 예:
    from ai_agent.backtest.adapters.random_decision import make_random_decision_fn
    decision_fn = make_random_decision_fn(seed=42)
    # DA framework run() 에 그대로 넘김
"""
from __future__ import annotations

import random
from typing import Any

from ..interfaces import Decision, Trigger


def make_random_decision_fn(
    seed: int = 42,
    weights: tuple[float, float, float] = (0.4, 0.2, 0.4),
    order_amount_range: tuple[int, int] = (100_000, 500_000),
):
    """DA DecisionFn 시그니처에 맞는 random baseline 생성기.

    weights: (buy_p, sell_p, hold_p), 합 1.0
    seed: 재현성 보장. 같은 (seed, weights)면 결과 동일.
    """
    if abs(sum(weights) - 1.0) > 1e-6:
        raise ValueError("weights는 합이 1이어야 합니다 (buy, sell, hold).")
    rng = random.Random(seed)

    def decision_fn(trigger: Trigger, user_context: dict, portfolio_snapshot: Any) -> Decision:
        choice = rng.choices(["buy", "sell", "hold"], weights=weights, k=1)[0]
        if choice == "hold":
            return Decision(action="hold", confidence=0.0, reasoning="random baseline")
        return Decision(
            action=choice,
            order_amount=rng.randint(*order_amount_range),
            confidence=0.5,
            reasoning="random baseline",
        )

    return decision_fn
