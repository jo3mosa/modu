"""risk_gate 단위 테스트.

검증:
- 기본 형식 유효성 (action / asset / side / order_amount)
- 자동매매 정책 (allow_auto_trade)
- 사용자 한도 정합성 hard rule:
    * BUY 손절 정합성 (stop_loss_pct)
    * SELL 익절 정합성 (take_profit_pct, 평단가 기준)
    * AI 운용 한도 단일 주문 (ai_budget_amount — None이면 skip)

테스트 전략:
- LLM-free 노드이므로 mocking 없이 risk_gate(state)를 직접 호출.
- InvestmentAgentState를 시나리오별로 구성하고 결과 dict를 검증.
"""
from typing import Any

from app.agents.decision.risk_gate import risk_gate
from app.state.investment_state import InvestmentAgentState
from app.state.schemas import FinalDecision


# ──────────────────────────────────────────
# 기본 fixture 빌더
# ──────────────────────────────────────────


def _decision(
    *,
    action: str = "trade",
    asset: str | None = "005930",
    side: str | None = "buy",
    order_amount: int | None = 500_000,
    target_price: float | None = 70_000,
    stop_loss_price: float | None = 66_500,  # -5% (기본 stop_loss_pct=5에 정확히 맞춤)
) -> FinalDecision:
    return FinalDecision(
        action=action,  # type: ignore[arg-type]
        asset=asset,
        side=side,  # type: ignore[arg-type]
        order_amount=order_amount,
        target_price=target_price,
        stop_loss_price=stop_loss_price,
        reason_summary="test",
    )


def _state(
    *,
    decision: FinalDecision | None = None,
    allow_auto_trade: bool = True,
    risk_rules: dict[str, Any] | None = None,
    portfolio_snapshot: dict[str, Any] | None = None,
) -> InvestmentAgentState:
    return InvestmentAgentState(
        final_decision=decision if decision is not None else _decision(),
        policy_context={"allow_auto_trade": allow_auto_trade},
        user_context={"risk_rules": risk_rules or {}},
        portfolio_snapshot=portfolio_snapshot or {},
    )


def _check(result: dict[str, Any], name: str) -> dict[str, Any] | None:
    for c in result["risk_check_result"]["checks"]:
        if c["name"] == name:
            return c
    return None


# ──────────────────────────────────────────
# 1. 기본 형식 & 자동매매
# ──────────────────────────────────────────


def test_hold_action_is_hold() -> None:
    state = _state(decision=_decision(
        action="hold", side=None, order_amount=None, target_price=None, stop_loss_price=None,
    ))
    result = risk_gate(state)
    assert result["flow_status"] == "hold"
    assert result["risk_cleared"] is False


def test_auto_trade_off_blocks_before_hard_rules() -> None:
    # 손절가가 명백히 위반이어도 자동매매 OFF가 먼저 잡혀야 함 (효율 + UX 명확)
    state = _state(
        decision=_decision(stop_loss_price=10_000),  # 명백히 위반
        allow_auto_trade=False,
        risk_rules={"stop_loss_pct": 5},
    )
    result = risk_gate(state)
    assert result["flow_status"] == "blocked"
    assert _check(result, "auto_trade_policy")["status"] == "blocked"
    # 한도 검증 단계엔 도달하지 않음
    assert _check(result, "buy_stop_loss_alignment") is None


def test_clean_buy_passes() -> None:
    result = risk_gate(_state(risk_rules={"stop_loss_pct": 5}))
    assert result["flow_status"] == "completed"
    assert result["risk_cleared"] is True
    assert _check(result, "buy_stop_loss_alignment")["status"] == "passed"


# ──────────────────────────────────────────
# 2. BUY 손절 정합성 (stop_loss_pct)
# ──────────────────────────────────────────


def test_buy_stop_loss_within_limit_passes() -> None:
    # target=70000, stop_loss_pct=5% → 최저 손절가 66500. 66500 == 66500 OK.
    result = risk_gate(_state(risk_rules={"stop_loss_pct": 5}))
    assert result["flow_status"] == "completed"
    assert _check(result, "buy_stop_loss_alignment")["status"] == "passed"


def test_buy_stop_loss_violation_blocks() -> None:
    # target=70000, stop_loss_pct=5% → 최저 손절가 66500. 60000은 위반.
    state = _state(
        decision=_decision(stop_loss_price=60_000),
        risk_rules={"stop_loss_pct": 5},
    )
    result = risk_gate(state)
    assert result["flow_status"] == "blocked"
    check = _check(result, "buy_stop_loss_alignment")
    assert check["status"] == "blocked"
    assert check["value"] == 60_000
    assert check["limit"] == 66_500.0


def test_buy_stop_loss_skipped_when_pct_missing() -> None:
    # 사용자 stop_loss_pct 미설정 → 검증 skip, 통과
    state = _state(
        decision=_decision(stop_loss_price=1_000),  # 위반 수치여도
        risk_rules={},  # stop_loss_pct 없음
    )
    result = risk_gate(state)
    assert result["flow_status"] == "completed"
    assert _check(result, "buy_stop_loss_alignment") is None


def test_buy_stop_loss_skipped_when_pct_zero() -> None:
    state = _state(
        decision=_decision(stop_loss_price=1_000),
        risk_rules={"stop_loss_pct": 0},
    )
    result = risk_gate(state)
    assert result["flow_status"] == "completed"
    assert _check(result, "buy_stop_loss_alignment") is None


# ──────────────────────────────────────────
# 3. SELL 익절 정합성 (take_profit_pct, 평단 기준)
# ──────────────────────────────────────────


def _sell_state(
    *,
    target_price: float,
    take_profit_pct: int | float | None,
    avg_price: float | None = 70_000,
    positions_key: str = "positions",
) -> InvestmentAgentState:
    portfolio = {}
    if avg_price is not None:
        portfolio[positions_key] = [
            {"stock_code": "005930", "average_price": avg_price, "quantity": 10},
        ]
    return _state(
        decision=_decision(
            side="sell",
            target_price=target_price,
            stop_loss_price=target_price * 0.9,  # SELL의 stop_loss는 검증 대상 아님
        ),
        risk_rules={"take_profit_pct": take_profit_pct} if take_profit_pct is not None else {},
        portfolio_snapshot=portfolio,
    )


def test_sell_take_profit_within_target_passes() -> None:
    # 평단 70000, 익절률 10% → 최저 목표가 77000. 77000 == 77000 OK.
    result = risk_gate(_sell_state(target_price=77_000, take_profit_pct=10))
    assert result["flow_status"] == "completed"
    assert _check(result, "sell_take_profit_alignment")["status"] == "passed"


def test_sell_take_profit_below_target_blocks() -> None:
    # 평단 70000, 익절률 10% → 최저 목표가 77000. 72000은 미달.
    result = risk_gate(_sell_state(target_price=72_000, take_profit_pct=10))
    assert result["flow_status"] == "blocked"
    check = _check(result, "sell_take_profit_alignment")
    assert check["status"] == "blocked"
    assert check["value"] == 72_000
    assert check["limit"] == 77_000.0


def test_sell_take_profit_skipped_when_no_avg_price() -> None:
    # 평단 없음 → 검증 skip
    result = risk_gate(_sell_state(target_price=10, take_profit_pct=10, avg_price=None))
    assert result["flow_status"] == "completed"
    assert _check(result, "sell_take_profit_alignment") is None


def test_sell_take_profit_reads_mock_holdings_key() -> None:
    # mock_trigger는 "holdings" 키로 채움 — risk_gate가 양쪽 지원하는지 확인
    result = risk_gate(_sell_state(
        target_price=72_000, take_profit_pct=10, positions_key="holdings",
    ))
    assert result["flow_status"] == "blocked"  # 평단 70000 기준 위반 잡혀야 함


# ──────────────────────────────────────────
# 4. AI 운용 한도 (ai_budget_amount, 단일 주문)
# ──────────────────────────────────────────


def test_ai_budget_skipped_when_not_set() -> None:
    # BE 컬럼 도입 전 — ai_budget_amount None → skip
    result = risk_gate(_state(risk_rules={"stop_loss_pct": 5}))
    assert result["flow_status"] == "completed"
    assert _check(result, "ai_budget_single_order") is None


def test_ai_budget_within_limit_passes() -> None:
    state = _state(
        decision=_decision(order_amount=500_000),
        risk_rules={"stop_loss_pct": 5, "ai_budget_amount": 1_000_000},
    )
    result = risk_gate(state)
    assert result["flow_status"] == "completed"
    assert _check(result, "ai_budget_single_order")["status"] == "passed"


def test_ai_budget_exceeded_blocks() -> None:
    state = _state(
        decision=_decision(order_amount=2_000_000),
        risk_rules={"stop_loss_pct": 5, "ai_budget_amount": 1_000_000},
    )
    result = risk_gate(state)
    assert result["flow_status"] == "blocked"
    check = _check(result, "ai_budget_single_order")
    assert check["status"] == "blocked"
    assert check["value"] == 2_000_000
    assert check["limit"] == 1_000_000


def test_ai_budget_zero_treated_as_unset() -> None:
    state = _state(
        decision=_decision(order_amount=2_000_000),
        risk_rules={"stop_loss_pct": 5, "ai_budget_amount": 0},
    )
    result = risk_gate(state)
    assert result["flow_status"] == "completed"
    assert _check(result, "ai_budget_single_order") is None
