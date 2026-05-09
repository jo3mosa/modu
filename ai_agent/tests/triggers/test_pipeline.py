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
    BearThesis,
    BullThesis,
    CriticFeedback,
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

def _mock_memory_agent(state):
    return {"memory_context": {"profile": "moderate risk taker"}}


def _mock_bull_researcher(state):
    return {
        "bull_thesis": BullThesis(
            asset="005930",
            recommended_side="buy",
            claim="RSI 과매도 + 거래량 급증",
            evidence=["RSI 28", "거래량 평균 대비 2.3배"],
            risks_acknowledged=["단기 변동성 확대"],
            confidence=0.75,
        )
    }


def _mock_bear_researcher(state):
    return {
        "bear_thesis": BearThesis(
            asset="005930",
            recommended_side="hold",
            claim="단기 진입은 리스크가 있음",
            evidence=["섹터 모멘텀 둔화"],
            counterpoints_to_bull=["거래량 급증이 일회성일 가능성"],
            confidence=0.5,
        )
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


def _mock_critic_agent(state):
    return {
        "critic_feedback": CriticFeedback(
            approved=True,
            risk_level="low",
            comments=["RSI 지표 신뢰도 높음", "거래량 지지 확인"],
        )
    }


def _mock_supervisor_hold(state):
    """Supervisor가 보류 결정 → risk_guard/executor 미실행"""
    return {
        "flow_status": "hold",
        "final_decision": FinalDecision(action="hold"),
    }


def _mock_supervisor_trade(state):
    """Supervisor가 매수 결정 → risk_guard로 전달"""
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
        ),
    }


def _mock_risk_guard_block(state):
    return {
        "risk_cleared": False,
        "risk_check_result": {"status": "blocked", "reason": "포지션 비중 초과"},
    }


def _mock_risk_guard_pass(state):
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
        assert event.trigger_type == TriggerType.MARKET_EVENT
        assert event.user_id == 1
        assert event.stock_code == "005930"

    def test_snapshots_not_empty(self):
        """시장/분석/포트폴리오 스냅샷이 모두 채워진다"""
        event = create_mock_user_trigger()
        assert event.market_snapshot
        assert event.analysis_snapshot
        assert event.candidate_assets
        assert event.portfolio_snapshot
        assert event.user_context

    def test_trigger_reason_is_list(self):
        """trigger_reason이 비어 있지 않은 리스트다"""
        event = create_mock_user_trigger()
        assert isinstance(event.trigger_reason, list)
        assert len(event.trigger_reason) > 0


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
        """UserTriggerEvent의 스냅샷이 그대로 state로 복사된다"""
        event = create_mock_user_trigger()
        state = build_state_from_user_trigger(event)
        assert state.market_snapshot == event.market_snapshot
        assert state.analysis_snapshot == event.analysis_snapshot
        assert state.candidate_assets == event.candidate_assets
        assert state.portfolio_snapshot == event.portfolio_snapshot
        assert state.user_context == event.user_context

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
        assert state.critic_feedback is None
        assert state.final_decision is None
        assert state.risk_cleared is False
        assert state.execution_result == {}


# ──────────────────────────────────────────
# Test 3: LangGraph invoke 흐름
# ──────────────────────────────────────────

_BASE_PATCHES = {
    "app.graph.builder.memory_agent": _mock_memory_agent,
    "app.graph.builder.bull_researcher": _mock_bull_researcher,
    "app.graph.builder.bear_researcher": _mock_bear_researcher,
    "app.graph.builder.strategy_manager": _mock_strategy_manager,
    "app.graph.builder.critic_agent": _mock_critic_agent,
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
        [경로 A] supervisor hold → END
        risk_guard, executor는 실행되지 않는다.
        """
        result = self._invoke({"app.graph.builder.supervisor_agent": _mock_supervisor_hold})
        assert result["flow_status"] == "hold"
        assert result["execution_result"] == {}

    def test_trade_risk_blocked_path(self):
        """
        [경로 B] supervisor trade → risk_guard block → END
        executor는 실행되지 않는다.
        """
        result = self._invoke({
            "app.graph.builder.supervisor_agent": _mock_supervisor_trade,
            "app.graph.builder.risk_guard": _mock_risk_guard_block,
        })
        assert result["risk_cleared"] is False
        assert result["flow_status"] != "completed"
        assert result["execution_result"] == {}

    def test_trade_risk_passed_path(self):
        """
        [경로 C] supervisor trade → risk_guard pass → executor → completed
        execution_result에 주문 결과가 담긴다.
        """
        result = self._invoke({
            "app.graph.builder.supervisor_agent": _mock_supervisor_trade,
            "app.graph.builder.risk_guard": _mock_risk_guard_pass,
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
        """user_id, stock_code 없이 UserTriggerEvent 생성 시 ValidationError"""
        with pytest.raises(ValidationError) as exc_info:
            UserTriggerEvent(
                event_id="invalid-001",
                trigger_type=TriggerType.MARKET_EVENT,
                trigger_reason=["테스트"],
                # user_id, stock_code 누락
            )
        missing = {e["loc"][0] for e in exc_info.value.errors()}
        assert "user_id" in missing
        assert "stock_code" in missing

    def test_invalid_trigger_type(self):
        """존재하지 않는 trigger_type 값은 ValidationError를 발생시킨다"""
        with pytest.raises(ValidationError):
            UserTriggerEvent(
                event_id="invalid-002",
                trigger_type="INVALID_TYPE",
                user_id=1,
                stock_code="005930",
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
