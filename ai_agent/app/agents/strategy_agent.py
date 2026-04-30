from typing import Any

from app.state.investment_state import InvestmentAgentState


def strategy_agent(state: InvestmentAgentState) -> dict[str, Any]:
    asset = state.candidate_assets[0] if state.candidate_assets else {}

    return {
        "strategy_draft": {
            "asset": asset.get("symbol", "UNKNOWN"),
            "side": "buy",
            "order_amount": 500_000,
            "target_price": 78_000,
            "stop_loss_price": 69_000,
            "reason": "기술적 신호와 과거 유사 패턴을 기반으로 1차 매수 전략을 생성했습니다.",
            "confidence": 0.72,
        }
    }