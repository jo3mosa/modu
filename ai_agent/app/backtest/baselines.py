"""검증용 baseline 결정 생성기.

목적: LLM 호출 없이 FinalDecision을 만들어 그래프 결과의 통계적 유의성을 평가한다.
"우리 그래프가 랜덤보다 나은가?" 검증이 안 되면 그래프 자체의 가치 주장 불가.

DecisionProvider 인터페이스: run_pipeline과 동일한 모양의 dict를 반환한다.
replay_runner는 모드(A/B/random)에 따라 provider를 교체하기만 하면 된다.
"""
import random
from typing import Any, Protocol

from app.graph.builder import GraphMode
from app.graph.runner import run_pipeline
from app.state.schemas import FinalDecision
from app.triggers.schemas import UserTriggerEvent


class DecisionProvider(Protocol):
    """결정 생성기. 결과는 run_pipeline의 반환값과 동일한 dict 형태."""

    mode_label: str

    def decide(self, event: UserTriggerEvent) -> dict[str, Any]: ...


class GraphDecisionProvider:
    """LangGraph 그래프(A 또는 B)로 결정 생성. 실 LLM 호출."""

    def __init__(self, mode: GraphMode = "A") -> None:
        self._mode = mode
        self.mode_label = mode

    def decide(self, event: UserTriggerEvent) -> dict[str, Any]:
        return run_pipeline(event, mode=self._mode)


class RandomDecisionProvider:
    """LLM 미호출 baseline. seed로 재현 가능.

    BUY/SELL/HOLD 비율은 그래프 결정 분포와 비교 가능하도록 weights로 조정 가능.
    target/stop은 현재가 알 수 없으므로 None — scorer는 execution_price만 본다.
    """

    mode_label = "random"

    def __init__(
        self,
        seed: int = 42,
        weights: tuple[float, float, float] = (0.4, 0.2, 0.4),
        order_amount_range: tuple[int, int] = (100_000, 500_000),
    ) -> None:
        if abs(sum(weights) - 1.0) > 1e-6:
            raise ValueError("weights는 합이 1이어야 합니다 (buy, sell, hold).")
        self._rng = random.Random(seed)
        self._weights = weights
        self._order_range = order_amount_range

    def decide(self, event: UserTriggerEvent) -> dict[str, Any]:
        choice = self._rng.choices(["buy", "sell", "hold"], weights=self._weights, k=1)[0]
        if choice == "hold":
            decision = FinalDecision(action="hold", confidence=0.0, user_message="random hold")
        else:
            decision = FinalDecision(
                action="trade",
                asset=event.stock_code,
                side=choice,
                order_amount=self._rng.randint(*self._order_range),
                target_price=None,
                stop_loss_price=None,
                confidence=0.5,
                reason_summary="random baseline",
                user_message="random baseline decision",
            )
        return {
            "final_decision": decision,
            "research_verdict": None,
            "flow_status": "completed",
        }
