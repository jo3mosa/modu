"""LangSmith trace 검증용 — debate_2 토론 흐름 1 trigger 실 LLM 호출.

목적:
- 새 TradingAgents 패턴(시간순 history, 인용·반박, 자기 일관성)이 실 LLM 호출
  흐름에서 의도대로 작동하는지 LangSmith trace로 시각 확인.
- DB/Postgres/Mongo 의존 없이 단일 trigger 실행 (context_loader stub으로 우회).
- Kafka 미사용 (DISABLE_AGENT_MESSAGE=1).

비용: bull×2 + bear×2 + strategy_manager + decision_manager = LLM 6회 호출 (~$0.01 미만).

실행:
    cd <repo root>
    python -m ai_agent.backtest.examples.dummy_debate_trace

확인:
- 콘솔: round_count, history 시간순 누적, final_decision
- LangSmith UI: https://smith.langchain.com → project "modu-mvp"
  → 가장 최근 run에서 그래프 노드 시퀀스 + 각 LLM 호출의 prompt/output 확인.
"""
from __future__ import annotations

import os
import sys
from datetime import datetime, timezone, timedelta
from pathlib import Path

# repo root + ai_agent를 sys.path에 추가 — 직접 실행해도 import 동작.
_REPO_ROOT = Path(__file__).resolve().parents[3]
_AI_AGENT = _REPO_ROOT / "ai_agent"
for p in (_REPO_ROOT, _AI_AGENT):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

# .env 로드 (GMS_KEY + LangSmith 키)
from dotenv import load_dotenv
for candidate in (_REPO_ROOT / ".env", _AI_AGENT / ".env"):
    if candidate.exists():
        load_dotenv(candidate, override=True)

# LangSmith trace 강제 활성화 — .env 기본값 false라 직접 켬.
os.environ["LANGCHAIN_TRACING_V2"] = "true"
# Kafka 미사용 (publish_agent_message가 DNS lookup으로 12초 낭비하는 것 방지)
os.environ.setdefault("DISABLE_AGENT_MESSAGE", "1")

from app.triggers.schemas import MarketTrigger, UserTriggerEvent  # noqa: E402
from app.triggers.state_factory import build_state_from_user_trigger  # noqa: E402
import app.graph.builder as builder_mod  # noqa: E402


def _stub_context_loader(state):
    """DB 우회 — context_loader가 채울 값을 인메모리 dummy로 반환.

    실제 DB(Postgres) 없이 그래프 실행 가능하게 함. 실제 운영에선 사용 X.
    """
    return {
        "user_context": {
            "investor_type": {"risk_grade": "moderate", "code": "moderate", "risk_score": 50},
            "risk_rules": {"stop_loss_pct": 0.05, "take_profit_pct": 0.10},
            "domestic_stock_risk_policy": {},
        },
        "policy_context": {
            "auto_trade_status": "active",
            "allow_auto_trade": True,
            "kill_switch": {"enabled": False, "triggered": False, "reason": None},
            "max_order_amount": 1_000_000,
            "system_trading_constraints": {},
            "asset_allocation": {},
            "market_rules": {},
            "domestic_stock_risk_policy": {},
        },
        "memory_context": {
            "query_basis": {"stock_codes": ["005930"], "sectors": [], "key_signals": ["RSI_OVERSOLD"]},
            "recent_decisions": [],
            "recent_loss_decisions": [],
            "lessons_aggregate": [],
            "loss_pattern_brief": "(과거 손실 패턴 없음)",
            "similar_decisions_table": [],
            "recent_post_mortems": [],
            "summary": "dummy 환경 — 메모리 비어있음",
        },
        "history_context": {
            "trade_history_wiki": "",
            "relevant_indicator_guides": {},
        },
    }


def main() -> int:
    # context_loader만 stub으로 교체. bull/bear/strategy/decision/risk_gate는 실 노드.
    builder_mod.context_loader = _stub_context_loader

    # debate_2 그래프 컴파일
    graph = builder_mod.build_investment_graph(mode="debate_2")

    # dummy trigger — 매수 신호 우세하게 구성해 LLM이 결정을 내리도록.
    now_kst = datetime.now(timezone(timedelta(hours=9)))
    event = UserTriggerEvent(
        user_id=99999,
        stock_code="005930",
        timestamp=now_kst,
        as_of=now_kst,
        trigger=MarketTrigger(
            rule_ids=["DUMMY-RSI-OVERSOLD"],
            trigger_reason=["RSI 과매도 + 골든크로스"],
        ),
        analysis_snapshot={
            "stock_code": "005930",
            "timestamp": now_kst.isoformat(),
            "signals": {
                "technical": {
                    "rsi": 32,
                    "macd": "골든크로스 직후",
                    "ma_status": "20일선이 60일선 상향 돌파",
                    "bollinger": "하단 터치 후 반등",
                },
                "fundamental": {
                    "per": 11.8,
                    "pbr": 1.05,
                    "roe": 0.14,
                    "debt_ratio": 0.32,
                },
                "event": [
                    {"date": "2025-01-02", "title": "삼성전자 차세대 메모리 양산 발표", "category": "positive"},
                ],
                "sentiment": {
                    "positive_ratio": 0.62,
                    "negative_ratio": 0.18,
                    "neutral_ratio": 0.20,
                    "sample_size": 240,
                },
            },
        },
        portfolio_snapshot={
            "cash": 10_000_000,
            "current_price": 70000,
            "holdings": {"005930": 100},
            "total_assets": 17_000_000,
        },
    )

    # state 변환 + 그래프 실행 (LangSmith가 자동 trace)
    state = build_state_from_user_trigger(event)
    result = graph.invoke(state)

    # 결과 요약 출력
    ds = result.get("investment_debate_state", {})
    print("\n=== 실행 완료 ===")
    print(f"LangSmith project: {os.getenv('LANGCHAIN_PROJECT', '(미설정)')}")
    print(f"LangSmith tracing: {os.getenv('LANGCHAIN_TRACING_V2')}")
    print(f"\n[investment_debate_state]")
    print(f"  round_count       = {ds.get('round_count', 0)}")
    print(f"  bull_history len  = {len(ds.get('bull_history', []))}")
    print(f"  bear_history len  = {len(ds.get('bear_history', []))}")
    print(f"  debate_rounds len = {len(ds.get('debate_rounds', []))}")
    history = ds.get("history", "") or ""
    print(f"  history (lines)   = {len(history.split(chr(10))) if history else 0}")
    if history:
        print(f"\n[시간순 토론 흐름 — 발언 앞 90자]")
        for i, line in enumerate(history.split("\n"), 1):
            print(f"  {i}. {line[:90]}{'...' if len(line) > 90 else ''}")

    fd = result.get("final_decision")
    rv = result.get("research_verdict")
    if rv is not None:
        print(f"\n[ResearchVerdict] winning_side={rv.winning_side}, recommended_side={rv.recommended_side}, confidence={rv.confidence}")
    if fd is not None:
        print(f"[FinalDecision] action={fd.action}, side={getattr(fd, 'side', None)}, asset={getattr(fd, 'asset', None)}, order_amount={getattr(fd, 'order_amount', None)}")

    print("\nLangSmith UI에서 확인:")
    print("  https://smith.langchain.com → Projects → modu-mvp → 최근 run")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
