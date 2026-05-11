"""
Pipeline 통합 검증 테스트

검증 항목:
  1. UserTriggerEvent 정상 생성
  2. InvestmentAgentState 변환
  3. LangGraph invoke 흐름 (에이전트 mock)
  4. 필수 필드 누락 시 ValidationError 발생
"""
from unittest.mock import patch

import pytest
from pydantic import ValidationError

from app.graph.builder import build_investment_graph
from app.state.schemas import (
    FinalDecision,
    ResearchVerdict,
    StrategyDraft,
)
from app.triggers.mock_trigger import create_mock_user_trigger
from app.triggers.schemas import TriggerType, UserTriggerEvent
from app.triggers.state_factory import build_state_from_user_trigger
from app.state.investment_state import InvestmentAgentState


# ──────────────────────────────────────────
# 테스트 3번에서 사용할 mock agent 함수들
# LLM 호출 없이 결정론적인 결과를 반환한다.
# ──────────────────────────────────────────

def _mock_context_loader(state):
    return {"memory_context": {"profile": "moderate risk taker"}}


_BULL_ARGUMENT = (
    "Bull Analyst: 005930은 RSI 28의 강한 과매도 구간이고 거래량이 평균 대비 2.3배로 급증해 "
    "단기 반등 가능성이 큽니다."
)
_BEAR_ARGUMENT = (
    "Bear Analyst: 거래량 급증이 일회성 이벤트일 가능성을 배제할 수 없고, 섹터 모멘텀 둔화로 "
    "추격 매수는 부적절합니다."
)


def _mock_bull_researcher(state):
    return {
        "investment_debate_state": {
            "history": _BULL_ARGUMENT,
            "bull_history": _BULL_ARGUMENT,
            "bear_history": "",
            "current_response": _BULL_ARGUMENT,
            "count": 1,
        }
    }


def _mock_bear_researcher(state):
    debate = state.investment_debate_state or {}
    history = debate.get("history", "")
    new_history = f"{history}\n{_BEAR_ARGUMENT}" if history else _BEAR_ARGUMENT
    return {
        "investment_debate_state": {
            "history": new_history,
            "bull_history": debate.get("bull_history", ""),
            "bear_history": _BEAR_ARGUMENT,
            "current_response": _BEAR_ARGUMENT,
            "count": debate.get("count", 0) + 1,
        }
    }


def _mock_strategy_manager(state):
    """
    Manager는 StrategyDraft를 후속 critic/supervisor에 전달해야 한다.
    실제 노드와 동일하게 research_verdict와 strategy_draft를 함께 반환한다.
    """
    return {
        "research_verdict": ResearchVerdict(
            winning_side="bull",
            asset="005930",
            recommended_side="buy",
            rationale="RSI 과매도 + 거래량 급증 패턴",
            key_bull_points=["RSI 28", "거래량 2.3배"],
            key_bear_points=["거래량 급증의 일회성 가능성"],
            confidence=0.7,
            order_amount=500_000,
            target_price=75_000,
            stop_loss_price=67_000,
        ),
        "strategy_draft": StrategyDraft(
            asset="005930",
            side="buy",
            order_amount=500_000,
            target_price=75_000,
            stop_loss_price=67_000,
            reason="RSI 과매도 + 거래량 급증",
            confidence=0.7,
        ),
    }


def _mock_decision_manager_hold(state):
    """Decision Manager가 보류 결정 → risk_gate/executor 미실행"""
    return {
        "flow_status": "hold",
        "final_decision": FinalDecision(action="hold"),
    }


def _mock_decision_manager_trade(state):
    """Decision Manager가 매수 결정 → risk_gate로 전달"""
    return {
        "flow_status": "running",
        "final_decision": FinalDecision(
            action="trade",
            asset="005930",
            side="buy",
            order_amount=500_000,
            target_price=75_000,
            stop_loss_price=67_000,
            reason_summary="RSI 과매도 + 거래량 급증 패턴",
            confidence=0.78,
            risk_level="low",
        ),
    }


def _mock_risk_gate_block(state):
    return {
        "risk_cleared": False,
        "risk_check_result": {"status": "blocked", "reason": "포지션 비중 초과"},
    }


def _mock_risk_gate_pass(state):
    return {
        "risk_cleared": True,
        "risk_check_result": {"status": "passed"},
    }


# ──────────────────────────────────────────
# Test 1: UserTriggerEvent 정상 생성
# ──────────────────────────────────────────

class TestUserTriggerEventCreation:
    def test_event_instance(self):
        """create_mock_user_trigger()가 UserTriggerEvent를 반환한다"""
        event = create_mock_user_trigger()
        assert isinstance(event, UserTriggerEvent)

    def test_required_fields(self):
        """필수 식별 필드가 올바른 값으로 채워진다"""
        event = create_mock_user_trigger()
        assert event.event_id == "mock-user-trigger-001"
        assert event.event_type == TriggerType.MARKET_EVENT
        assert event.user_id == 1
        assert event.stock_code == "005930"

    def test_snapshots_not_empty(self):
        """분석/포트폴리오 스냅샷과 trigger 메타가 모두 채워진다"""
        event = create_mock_user_trigger()
        assert event.analysis_snapshot
        assert event.portfolio_snapshot
        assert event.user_context
        assert event.trigger.rule_ids
        assert event.trigger.trigger_reason

    def test_trigger_reason_is_list(self):
        """trigger.trigger_reason이 비어 있지 않은 리스트다 (DA 명세 nested 구조)"""
        event = create_mock_user_trigger()
        assert isinstance(event.trigger.trigger_reason, list)
        assert len(event.trigger.trigger_reason) > 0
        assert isinstance(event.trigger.rule_ids, list)
        assert len(event.trigger.rule_ids) > 0


# ──────────────────────────────────────────
# Test 2: InvestmentAgentState 변환
# ──────────────────────────────────────────

class TestStateConversion:
    def test_state_instance(self):
        """build_state_from_user_trigger()가 InvestmentAgentState를 반환한다"""
        event = create_mock_user_trigger()
        state = build_state_from_user_trigger(event)
        assert isinstance(state, InvestmentAgentState)

    def test_snapshots_transferred(self):
        """UserTriggerEvent의 스냅샷이 state로 복사되고, candidate_assets는 stock_code 기반 자동 생성된다"""
        event = create_mock_user_trigger()
        state = build_state_from_user_trigger(event)
        assert state.analysis_snapshot == event.analysis_snapshot
        assert state.portfolio_snapshot == event.portfolio_snapshot
        assert state.user_context == event.user_context
        # candidate_assets는 DA 명세에 없어서 state_factory가 stock_code 기반으로 구성
        assert state.candidate_assets == [{"stock_code": event.stock_code}]

    def test_initial_flow_status_is_running(self):
        """그래프 실행 시작 전 flow_status는 'running'이다"""
        event = create_mock_user_trigger()
        state = build_state_from_user_trigger(event)
        assert state.flow_status == "running"

    def test_agent_output_fields_are_empty(self):
        """변환 직후 에이전트 출력 필드는 모두 미설정 상태다"""
        event = create_mock_user_trigger()
        state = build_state_from_user_trigger(event)
        assert state.strategy_draft is None
        assert state.research_verdict is None
        assert state.final_decision is None
        assert state.risk_cleared is False
        assert state.execution_result == {}


# ──────────────────────────────────────────
# Test 3: LangGraph invoke 흐름
# ──────────────────────────────────────────

_BASE_PATCHES = {
    "app.graph.builder.context_loader": _mock_context_loader,
    "app.graph.builder.bull_researcher": _mock_bull_researcher,
    "app.graph.builder.bear_researcher": _mock_bear_researcher,
    "app.graph.builder.strategy_manager": _mock_strategy_manager,
}


class TestLangGraphFlow:
    def _invoke(self, extra_patches: dict):
        patches = {**_BASE_PATCHES, **extra_patches}
        with patch.multiple("app.graph.builder", **{
            k.split(".")[-1]: v for k, v in patches.items()
        }):
            event = create_mock_user_trigger()
            state = build_state_from_user_trigger(event)
            graph = build_investment_graph()
            return graph.invoke(state)

    def test_hold_path(self):
        """
        [경로 A] decision_manager hold → END
        risk_gate, executor는 실행되지 않는다.
        """
        result = self._invoke({"app.graph.builder.decision_manager": _mock_decision_manager_hold})
        assert result["flow_status"] == "hold"
        assert result["execution_result"] == {}

    def test_trade_risk_blocked_path(self):
        """
        [경로 B] decision_manager trade → risk_gate block → END
        executor는 실행되지 않는다.
        """
        result = self._invoke({
            "app.graph.builder.decision_manager": _mock_decision_manager_trade,
            "app.graph.builder.risk_gate": _mock_risk_gate_block,
        })
        assert result["risk_cleared"] is False
        assert result["flow_status"] != "completed"
        assert result["execution_result"] == {}

    def test_trade_risk_passed_path(self):
        """
        [경로 C] decision_manager trade → risk_gate pass → executor → completed
        execution_result에 주문 결과가 담긴다.
        """
        result = self._invoke({
            "app.graph.builder.decision_manager": _mock_decision_manager_trade,
            "app.graph.builder.risk_gate": _mock_risk_gate_pass,
        })
        assert result["risk_cleared"] is True
        assert result["flow_status"] == "completed"
        assert result["execution_result"]["status"] == "success"
        assert result["execution_result"]["asset"] == "005930"


# ──────────────────────────────────────────
# Test 4: 필수 필드 누락 ValidationError
# ──────────────────────────────────────────

class TestValidationError:
    def test_missing_user_id_and_stock_code(self):
        """user_id, stock_code, timestamp 없이 UserTriggerEvent 생성 시 ValidationError"""
        with pytest.raises(ValidationError) as exc_info:
            UserTriggerEvent(
                event_type=TriggerType.MARKET_EVENT,
                # user_id, stock_code, timestamp 누락
            )
        missing = {e["loc"][0] for e in exc_info.value.errors()}
        assert "user_id" in missing
        assert "stock_code" in missing
        assert "timestamp" in missing

    def test_invalid_trigger_type(self):
        """존재하지 않는 event_type 값은 ValidationError를 발생시킨다"""
        from datetime import datetime, timezone
        with pytest.raises(ValidationError):
            UserTriggerEvent(
                event_type="INVALID_TYPE",
                user_id=1,
                stock_code="005930",
                timestamp=datetime.now(tz=timezone.utc),
            )

    def test_strategy_hold_with_nonzero_amount(self):
        """side=hold인데 order_amount != 0이면 ValidationError"""
        with pytest.raises(ValidationError):
            StrategyDraft(
                asset="005930",
                side="hold",
                order_amount=100_000,
            )

    def test_strategy_buy_without_prices(self):
        """side=buy인데 target_price/stop_loss_price 누락 시 ValidationError"""
        with pytest.raises(ValidationError):
            StrategyDraft(
                asset="005930",
                side="buy",
                order_amount=500_000,
                # target_price, stop_loss_price 누락
            )

    def test_research_verdict_hold_with_nonzero_amount(self):
        """recommended_side=hold인데 order_amount가 0이 아니면 ValidationError"""
        with pytest.raises(ValidationError):
            ResearchVerdict(
                winning_side="bear",
                asset="005930",
                recommended_side="hold",
                order_amount=100_000,
            )

    def test_research_verdict_buy_without_prices(self):
        """recommended_side=buy인데 target_price/stop_loss_price 누락 시 ValidationError"""
        with pytest.raises(ValidationError):
            ResearchVerdict(
                winning_side="bull",
                asset="005930",
                recommended_side="buy",
                order_amount=500_000,
                # target_price, stop_loss_price 누락
            )

    def test_research_verdict_buy_with_nonpositive_target(self):
        """recommended_side=buy인데 target_price가 0 이하이면 ValidationError"""
        with pytest.raises(ValidationError):
            ResearchVerdict(
                winning_side="bull",
                asset="005930",
                recommended_side="buy",
                order_amount=500_000,
                target_price=0,
                stop_loss_price=67_000,
            )

    def test_research_verdict_buy_with_nonpositive_stop_loss(self):
        """recommended_side=buy인데 stop_loss_price가 0 이하이면 ValidationError"""
        with pytest.raises(ValidationError):
            ResearchVerdict(
                winning_side="bull",
                asset="005930",
                recommended_side="buy",
                order_amount=500_000,
                target_price=75_000,
                stop_loss_price=0,
            )
