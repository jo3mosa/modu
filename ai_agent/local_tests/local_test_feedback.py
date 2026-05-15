"""
더미 데이터로 Decision → Feedback(Post-Mortem) 흐름을 한 번에 실행하는 스크립트.

흐름:
    local_test.run_decision_pipeline()
        → final_state (decision graph 결과)
        → _state_to_decision_context() 로 ai_judgments 컬럼 형태로 변환
        → _load_decision_context monkeypatch
        → run_post_mortem() (실제 LLM 호출)
        → 모킹된 store에 INSERT

실행 방법:
    PYTHONUTF8=1 conda run -n modu-ai python local_tests/local_test_feedback.py
    (ai_agent/ 디렉토리에서 실행)

필요한 ai_agent/.env 설정:
    GMS_KEY=<SSAFY GMS 키>
    LANGCHAIN_TRACING_V2=true   # LangSmith 사용 시
    LANGCHAIN_API_KEY=<키>      # LangSmith 사용 시

PnL 3개(raw_return / alpha_return / holding_days)는 백엔드가 매도 체결 후 계산하는
값이라 ai_agent 그래프에서 만들 수 없다. 이 스크립트에서도 더미 상수를 그대로 쓴다.
"""
import os
import sys
from datetime import datetime, timezone
from typing import Any

# ── 더미 입력: BE가 발행한다고 가정한 trade.settled 이벤트 ──────────────────
# 결정 결과(final_decision)는 local_test의 그래프에서 받아오지만, PnL은 매도 체결
# 후 백엔드만 알 수 있으므로 여기서는 더미 상수로 고정한다.
DUMMY_TRADE_SETTLED = {
    "user_id": 1,
    "ai_judgment_id": 12345,       # 로컬 DB가 없으므로 monkeypatch가 이 값을 무시한다
    "trade_pnl_record_id": 789,
    "raw_return": -0.034,          # -3.4% 손실
    "alpha_return": -0.021,        # 시장 대비 -2.1%
    "holding_days": 5,
}


# ── 모킹된 DB store (DBMemoryStore 대체) ───────────────────────────────────
class _DummyMemoryStore:
    """store_postmortem 호출만 받아 record를 보관한다. 실제 DB 안 씀."""

    def __init__(self) -> None:
        self.last_record: dict | None = None

    def store_postmortem(self, record: dict) -> int:
        self.last_record = record
        return 999  # 가짜 record_id


# ── 출력 유틸 ──────────────────────────────────────────────────────────────
SEP = "─" * 62


def _section(title: str) -> None:
    print(f"\n{SEP}")
    print(f"  {title}")
    print(SEP)


def _row(label: str, value, indent: int = 2) -> None:
    print(f"{'  ' * indent}{label:<24}{value}")


def _truncate(text: str, max_len: int = 200) -> str:
    text = (text or "").strip()
    if len(text) <= max_len:
        return text
    return text[:max_len] + f" ... ({len(text) - max_len}자 생략)"


# ── final_state → ai_judgments 컬럼 형태로 변환 ────────────────────────────
def _state_to_decision_context(final_state: dict[str, Any]) -> dict[str, Any] | None:
    """
    Decision graph의 final_state를 _load_decision_context와 동일한 반환 형태로 변환한다.

    - decision_content: judgment_reason + bull_claim + bear_claim + winning_side 결합
      (실제 _load_decision_context가 ai_judgments 컬럼을 합치는 방식과 동일)
    - risk_grade: final_decision.risk_level (low/medium/high)
    - key_signals: memory_context.query_basis.key_signals

    final_decision.action != "trade" 이면 None을 반환한다 (회고 대상 아님).
    """
    decision = final_state.get("final_decision")
    if decision is None:
        return None
    d = decision if isinstance(decision, dict) else decision.model_dump()
    if d.get("action") != "trade":
        return None

    debate = final_state.get("investment_debate_state") or {}
    bull_history = (debate.get("bull_history") or "").strip()
    bear_history = (debate.get("bear_history") or "").strip()

    verdict = final_state.get("research_verdict")
    v = (verdict if isinstance(verdict, dict) else verdict.model_dump()) if verdict else {}
    winning_side = v.get("winning_side", "")

    parts: list[str] = []
    reason_summary = (d.get("reason_summary") or "").strip()
    if reason_summary:
        parts.append(f"[판단 사유]\n{reason_summary}")
    if bull_history:
        parts.append(f"[Bull 주장]\n{bull_history}")
    if bear_history:
        parts.append(f"[Bear 주장]\n{bear_history}")
    if winning_side:
        parts.append(f"[우세 관점]\n{winning_side}")

    key_signals = (
        (final_state.get("memory_context") or {})
        .get("query_basis", {})
        .get("key_signals", [])
    )

    return {
        "decision_content": "\n\n".join(parts) if parts else "(판단 사유 없음)",
        "risk_grade": d.get("risk_level"),
        "key_signals": key_signals,
    }


# ── 입력 요약 출력 ─────────────────────────────────────────────────────────
def _print_input(event, decision_context: dict[str, Any]) -> None:
    _section("회고 입력 (decision graph 결과 + 더미 PnL)")

    print("  [TradeSettledEvent — BE가 발행했다고 가정]")
    _row("user_id", event.user_id, 2)
    _row("ai_judgment_id", event.ai_judgment_id, 2)
    _row("trade_pnl_record_id", event.trade_pnl_record_id, 2)
    _row("raw_return", f"{event.raw_return:+.1%}  (절대 수익률)", 2)
    _row("alpha_return", f"{event.alpha_return:+.1%}  (시장 대비)", 2)
    _row("holding_days", f"{event.holding_days}일", 2)

    print("\n  [결정 컨텍스트 — local_test의 final_state에서 추출]")
    _row("risk_grade", decision_context["risk_grade"], 2)
    _row("key_signals", decision_context["key_signals"], 2)
    print("    decision_content:")
    for line in decision_context["decision_content"].splitlines():
        print(f"      {line}")


# ── 결과 출력 ──────────────────────────────────────────────────────────────
def _print_record(record: dict | None, record_id: int | None) -> None:
    _section("회고 결과 (PostMortemReflection → PostMortemRecord)")

    if record is None:
        print("  ※ 회고 생성 실패 (silent skip — None 반환)")
        print("    원인 후보: LLM 호출 실패 / 출력 파싱 2회 실패 / DB 저장 실패")
        return

    _row("ai_judgment_id", record.get("ai_judgment_id"), 2)
    _row("trade_pnl_record_id", record.get("trade_pnl_record_id"), 2)
    print()
    print("  [평가]")
    _row("entry_timing", _truncate(record.get("entry_timing_assessment", ""), 150), 2)
    _row("exit_rule", _truncate(record.get("exit_rule_assessment", ""), 150), 2)
    _row("risk_prediction", _truncate(record.get("risk_prediction_accuracy", ""), 150), 2)

    print("\n  [놓친 시그널]")
    for s in record.get("missed_signals", []) or ["(없음)"]:
        print(f"    ✗ {s}")

    print("\n  [다음 결정에 적용할 교훈]")
    for s in record.get("lessons", []) or ["(없음)"]:
        print(f"    ✓ {s}")

    print()
    _row("summary", _truncate(record.get("summary", ""), 200), 2)
    print()
    _row("→ DB record_id", record_id if record_id else "(저장 실패)", 2)


# ── 메인 ──────────────────────────────────────────────────────────────────
def main() -> None:
    # local_tests/ 안에서 동일 디렉토리의 local_test.py와 부모(ai_agent/)의 app.* 둘 다 import
    here = os.path.dirname(__file__)
    parent = os.path.dirname(here)
    for path in (here, parent):
        if path not in sys.path:
            sys.path.insert(0, path)

    from local_test import run_decision_pipeline

    # [1] Decision graph 실행 → final_state 수집 (verbose 출력 그대로 보여줌)
    final_state = run_decision_pipeline(verbose=True)

    # [2] hold이면 회고할 게 없으므로 명시적으로 종료
    decision_context = _state_to_decision_context(final_state)
    if decision_context is None:
        decision = final_state.get("final_decision")
        d = (
            decision if isinstance(decision, dict)
            else decision.model_dump() if decision else {}
        )
        _section("회고 단계 skip")
        print("  ✗ final_decision.action != 'trade' (또는 결정 없음)")
        _row("action", d.get("action") or "(없음)", 2)
        _row("reason_summary", _truncate(d.get("reason_summary", ""), 150), 2)
        print("\n  회고는 매수→매도 cycle 종료가 트리거이므로 hold/누락에는 회고하지 않습니다.")
        return

    # [3] feedback 파이프라인 진입. _load_decision_context를 monkeypatch로
    #     "위에서 합성한 decision_context를 그대로 반환"하게 만든다.
    import app.feedback.pipeline as pipeline_module

    pipeline_module._load_decision_context = (
        lambda ai_judgment_id, engine: decision_context
    )

    from app.feedback.pipeline import run_post_mortem
    from app.feedback.schemas import TradeSettledEvent

    event = TradeSettledEvent(
        timestamp=datetime.now(tz=timezone.utc),
        **DUMMY_TRADE_SETTLED,
    )

    tracing = os.getenv("LANGCHAIN_TRACING_V2", "false")
    _section(f"Feedback Agent 회고 실행  |  LangSmith: {tracing}")
    _row("ai_judgment_id", event.ai_judgment_id, 1)
    _row("LangSmith 프로젝트", os.getenv("LANGCHAIN_PROJECT", "-"), 1)

    _print_input(event, decision_context)

    _section("회고 파이프라인 실행")
    print("  [1] _load_decision_context  → local_test의 final_state에서 합성 (DB 우회)")
    print("  [2] post_mortem_agent       → LLM 호출 (실제)")
    print("  [3] store.store_postmortem  → 모의 INSERT (DB 우회)")

    store = _DummyMemoryStore()
    record_id = run_post_mortem(
        event=event,
        engine=None,           # 모킹된 store만 사용하므로 engine 무시됨
        memory_store=store,
    )

    _print_record(store.last_record, record_id)


if __name__ == "__main__":
    main()
