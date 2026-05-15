"""
post_mortem_agent 단위 테스트.

검증:
- 정상 입력에 대해 PostMortemReflection 그대로 반환
- 출력 파싱 2회 실패 시 None 반환 (silent skip)
- LLM 호출 실패 시 None 반환 (silent skip)

테스트 전략:
- `_build_chain`을 monkeypatch해서 LLM 호출 없이 결정론적으로 동작시킨다.
- decision_manager / strategy_manager 와 동일한 retry-once 정책을 검증.
"""
from langchain_core.exceptions import OutputParserException

from app.agents.feedback import post_mortem_agent as pm_module
from app.agents.feedback.post_mortem_agent import post_mortem_agent
from app.feedback.schemas import PostMortemReflection


_SAMPLE_DECISION = "RSI 28의 강한 과매도 + 거래량 2.3배 급증 패턴에 따라 매수 결정"
_SAMPLE_REFLECTION = PostMortemReflection(
    entry_timing_assessment="RSI 28의 과매도 신호에 정확히 진입했고 거래량 확증 후 1거래일 지연 진입이었음.",
    exit_rule_assessment="익절가 75,000원 도달 후 청산. 손절가 67,000원은 발동되지 않음.",
    risk_prediction_accuracy="당시 low로 평가한 리스크가 실제 변동성과 부합 (홀딩 기간 std 2.1%).",
    missed_signals=["섹터 모멘텀 둔화 시그널"],
    lessons=["거래량 급증 확인 후 1거래일 lead time을 두면 false breakout 방어"],
    summary="과매도 진입 정확, 익절 도달. 다음에는 거래량 확증 lead time 도입 검토.",
)


class _FakeChain:
    """`_build_chain()` 대체용. invoke가 미리 지정된 결과를 반환하거나 예외를 던진다."""

    def __init__(self, *, result=None, exceptions: list[Exception] | None = None) -> None:
        self._result = result
        self._exceptions = list(exceptions or [])
        self.invoke_count = 0

    def invoke(self, _inputs):
        self.invoke_count += 1
        if self._exceptions:
            raise self._exceptions.pop(0)
        return self._result


def _patch_chain(monkeypatch, chain: _FakeChain) -> None:
    monkeypatch.setattr(pm_module, "_build_chain", lambda: chain)


class TestPostMortemAgentSuccess:
    def test_returns_reflection_when_llm_succeeds(self, monkeypatch):
        chain = _FakeChain(result=_SAMPLE_REFLECTION)
        _patch_chain(monkeypatch, chain)

        result = post_mortem_agent(
            decision_content=_SAMPLE_DECISION,
            raw_return=0.052,
            alpha_return=0.021,
            holding_days=14,
            risk_level="low",
            key_signals=["technical_signal", "sentiment_signal"],
        )

        assert result is _SAMPLE_REFLECTION
        assert chain.invoke_count == 1


class TestPostMortemAgentFailure:
    def test_returns_none_after_parser_exception_twice(self, monkeypatch):
        """1회 재시도 후에도 파싱 실패면 None 반환."""
        chain = _FakeChain(
            exceptions=[
                OutputParserException("first invalid json"),
                OutputParserException("second invalid json"),
            ],
        )
        _patch_chain(monkeypatch, chain)

        result = post_mortem_agent(
            decision_content=_SAMPLE_DECISION,
            raw_return=0.052,
            alpha_return=0.021,
            holding_days=14,
        )

        assert result is None
        assert chain.invoke_count == 2

    def test_returns_reflection_when_parser_recovers_on_retry(self, monkeypatch):
        """1차 파싱 실패 → 2차에서 성공하면 정상 반환."""
        chain = _FakeChain(
            result=_SAMPLE_REFLECTION,
            exceptions=[OutputParserException("first invalid json")],
        )
        _patch_chain(monkeypatch, chain)

        result = post_mortem_agent(
            decision_content=_SAMPLE_DECISION,
            raw_return=0.052,
            alpha_return=0.021,
            holding_days=14,
        )

        assert result is _SAMPLE_REFLECTION
        assert chain.invoke_count == 2

    def test_returns_none_on_llm_invocation_failure(self, monkeypatch):
        """LLM 호출 자체 실패(타임아웃·네트워크 등)는 1회로 silent skip."""
        chain = _FakeChain(
            exceptions=[RuntimeError("API connection error")],
        )
        _patch_chain(monkeypatch, chain)

        result = post_mortem_agent(
            decision_content=_SAMPLE_DECISION,
            raw_return=-0.034,
            alpha_return=-0.012,
            holding_days=7,
        )

        assert result is None
        assert chain.invoke_count == 1
