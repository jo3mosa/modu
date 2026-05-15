"""
더미 데이터로 Feedback (Post-Mortem) Agent를 실행하는 개발/테스트용 스크립트.

실행 방법:
    PYTHONUTF8=1 conda run -n modu-ai python local_tests/local_test_feedback.py
    (ai_agent/ 디렉토리에서 실행)

필요한 ai_agent/.env 설정:
    GMS_KEY=<SSAFY GMS 키>
    LANGCHAIN_TRACING_V2=true   # LangSmith 사용 시
    LANGCHAIN_API_KEY=<키>      # LangSmith 사용 시

기존 local_test.py와의 차이:
    local_test.py          → Decision graph 전체 (context → bull → bear → ... → risk_gate)
    local_test_feedback.py → Feedback 단발 회고 (TradeSettledEvent → post_mortem LLM → DB INSERT)
"""
import os
import sys
from datetime import datetime, timezone

# ── 더미 입력 1: BE가 발행한다고 가정한 trade.settled 이벤트 ─────────────────
DUMMY_TRADE_SETTLED = {
    "user_id": 1,
    "ai_judgment_id": 12345,
    "trade_pnl_record_id": 789,
    "raw_return": -0.034,         # -3.4% 손실
    "alpha_return": -0.021,       # 시장 대비 -2.1% (벤치마크보다 더 나쁨)
    "holding_days": 5,
}

# ── 더미 입력 2: ai_judgments에서 조회됐다고 가정한 과거 결정 컨텍스트 ─────────
# pipeline._load_decision_context 의 반환 형태를 그대로 모방한다.
DUMMY_DECISION_CONTEXT = {
    "decision_content": (
        "[판단 사유]\n"
        "RSI 28의 과매도 + 거래량 급증 패턴으로 단기 반등 가능성이 높다고 판단.\n"
        "외국인 순매수 전환 신호도 함께 확인됨. 75,000원 매수, 80,000원 익절, 67,000원 손절.\n\n"
        "[Bull 주장]\n"
        "Bull Analyst: RSI 28의 강한 과매도 구간, 외국인 순매수 전환, 반도체 섹터 모멘텀 회복.\n\n"
        "[Bear 주장]\n"
        "Bear Analyst: 거래량 급증이 일회성 이벤트일 가능성, 글로벌 매크로 악재 잔존, "
        "실적 발표 직전 단기 변동성 확대 우려.\n\n"
        "[우세 관점]\nbull"
    ),
    "risk_grade": "low",
    "key_signals": ["technical_signal", "sentiment_signal"],
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


# ── 입력 요약 출력 ─────────────────────────────────────────────────────────
def _print_input(event) -> None:
    _section("입력 더미 데이터")

    print("  [TradeSettledEvent — BE가 발행했다고 가정]")
    _row("user_id", event.user_id, 2)
    _row("ai_judgment_id", event.ai_judgment_id, 2)
    _row("trade_pnl_record_id", event.trade_pnl_record_id, 2)
    _row("raw_return", f"{event.raw_return:+.1%}  (절대 수익률)", 2)
    _row("alpha_return", f"{event.alpha_return:+.1%}  (시장 대비)", 2)
    _row("holding_days", f"{event.holding_days}일", 2)

    print("\n  [과거 결정 컨텍스트 — ai_judgments에서 조회되었다고 가정]")
    _row("risk_grade", DUMMY_DECISION_CONTEXT["risk_grade"], 2)
    _row("key_signals", DUMMY_DECISION_CONTEXT["key_signals"], 2)
    print("    decision_content:")
    for line in DUMMY_DECISION_CONTEXT["decision_content"].splitlines():
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
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    # local_tests/ 안에 있으므로 부모(ai_agent/)를 path에 추가해 app.* import 가능하게 한다
    sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
    os.environ.setdefault("LANGCHAIN_PROJECT", "modu-mvp")
    os.environ.setdefault("DATABASE_URL", "postgresql://dummy:dummy@localhost/dummy")

    # _load_decision_context를 monkeypatch — DB 없이 더미 반환
    import app.feedback.pipeline as pipeline_module

    pipeline_module._load_decision_context = (
        lambda ai_judgment_id, engine: DUMMY_DECISION_CONTEXT
    )

    from app.feedback.pipeline import run_post_mortem
    from app.feedback.schemas import TradeSettledEvent

    event = TradeSettledEvent(
        timestamp=datetime.now(tz=timezone.utc),
        **DUMMY_TRADE_SETTLED,
    )

    tracing = os.getenv("LANGCHAIN_TRACING_V2", "false")
    _section(f"Feedback Agent 더미 회고 실행  |  LangSmith: {tracing}")
    _row("ai_judgment_id", event.ai_judgment_id, 1)
    _row("LangSmith 프로젝트", os.getenv("LANGCHAIN_PROJECT", "-"), 1)

    _print_input(event)

    _section("회고 파이프라인 실행")
    print("  [1] _load_decision_context  → 더미 컨텍스트 반환 (DB 우회)")
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
