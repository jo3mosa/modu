"""
더미 데이터로 AI Agent 전체 파이프라인을 실행하는 개발/테스트용 스크립트.

실행 방법:
    PYTHONUTF8=1 conda run -n modu-ai python local_tests/local_test.py
    (ai_agent/ 디렉토리에서 실행)

필요한 ai_agent/.env 설정:
    GMS_KEY=<SSAFY GMS 키>
    LANGCHAIN_TRACING_V2=true   # LangSmith 사용 시
    LANGCHAIN_API_KEY=<키>      # LangSmith 사용 시
"""
import os
import sys

# ── 더미 입력 신호 ─────────────────────────────────────────────────────────
# analysis_snapshot은 반드시 "signals" 키 아래 4개 분야로 구성해야 합니다.
# bull_researcher와 extract_key_signals 모두 signals.get("technical") 형태로 접근합니다.
DUMMY_ANALYSIS_SNAPSHOT = {
    "stock_code": "005930",
    "timestamp": "2026-05-12T08:00:00Z",
    "signals": {
        "technical": {
            "trend": {
                "sma_alignment": "bullish",
                "macd_state": "bullish_cross",
            },
            "momentum": {"rsi_14": 28.5},       # 30 미만: 과매도 구간
            "volatility": {
                "bollinger_position": "lower_breakout",
                "atr_ratio": 0.025,
            },
            "volume": {"mfi_14": 18.0},          # 20 미만: 자금 유입 약함
        },
        "fundamental": {
            "valuation": {"per": 12.5, "pbr": 1.1, "status": "undervalued"},
            "profitability": {"roe": 15.2, "status": "high_margin"},
            "growth": {"status": "steady_growth"},
            "stability": {"status": "stable"},
        },
        "event": {
            "has_urgent_issue": False,
            "recent_disclosures": [],
        },
        "sentiment": {
            "daily_score": 0.67,
            "confidence_level": "high",
            "pos_prob": 0.70,
            "neu_prob": 0.20,
            "neg_prob": 0.10,
        },
    },
}

DUMMY_PORTFOLIO_SNAPSHOT = {
    "cash_balance": 3_000_000,
    "total_assets": 5_000_000,
    "holdings": [],
}

# ── 더미 컨텍스트 (context_loader가 DB 대신 반환하는 값) ─────────────────────
# allow_auto_trade=True 여야 risk_gate가 passed로 통과합니다.
DUMMY_CONTEXT: dict = {
    "user_context": {
        "investor_type": {
            "risk_grade": "active",
            "code": "active",
            "risk_score": 80,
            "investment_goal": "growth",
            "answers_snapshot": None,
        },
        "risk_rules": {
            "stop_loss_pct": 5.0,
            "take_profit_pct": 10.0,
        },
        "domestic_stock_risk_policy": {
            "normal_listed_stock": {
                "risk_grade": 2,
                "label": "고위험",
                "auto_buy_policy": "allowed_with_constraints",
            },
        },
    },
    "policy_context": {
        "auto_trade_status": "active",
        "allow_auto_trade": True,       # False 이면 risk_gate가 blocked로 종료
        "kill_switch": {"enabled": False, "triggered": False, "reason": None},
        "max_order_amount": 1_000_000,
        "system_trading_constraints": {
            "max_number_of_positions": 3,
            "minimum_cash_ratio": {"value": 10, "unit": "%"},
            "investor_type_constraints": {
                "active": {"max_single_stock_ratio": 20},
            },
        },
        "asset_allocation": {
            "max_single_stock_ratio": 20,
            "max_number_of_positions": 3,
            "minimum_cash_ratio": 10,
        },
        "market_rules": {
            "restrict_new_entry_when": ["market_index_sharp_drop"],
            "market_drop_threshold": -3.0,
        },
        "domestic_stock_risk_policy": {
            "normal_listed_stock": {
                "risk_grade": 2,
                "label": "고위험",
                "auto_buy_policy": "allowed_with_constraints",
            },
        },
    },
    "memory_context": {
        "query_basis": {
            "stock_codes": ["005930"],
            "sectors": ["semiconductor"],
            "key_signals": ["technical_signal", "sentiment_signal"],
        },
        "recent_decisions": [],
        "recent_loss_decisions": [],
        "summary": "최근 유사 판단 이력 없음 (더미 데이터).",
    },
    "history_context": {
        "trade_history_wiki": "",
        "relevant_indicator_guides": {},
    },
}


# ── 출력 유틸 ──────────────────────────────────────────────────────────────

SEP = "─" * 62

def _section(title: str) -> None:
    print(f"\n{SEP}")
    print(f"  {title}")
    print(SEP)


def _row(label: str, value, indent: int = 2) -> None:
    print(f"{'  ' * indent}{label:<22}{value}")


def _truncate(text: str, max_len: int = 280) -> str:
    text = text.strip()
    if len(text) <= max_len:
        return text
    return text[:max_len] + f"\n  ... (이하 {len(text) - max_len}자 생략)"


# ── 입력 요약 출력 ─────────────────────────────────────────────────────────

def _print_input() -> None:
    _section("입력 더미 데이터")
    signals = DUMMY_ANALYSIS_SNAPSHOT["signals"]
    tech = signals["technical"]
    fund = signals["fundamental"]
    sent = signals["sentiment"]

    print("  [기술적 신호]")
    _row("RSI(14)", f'{tech["momentum"]["rsi_14"]}  ← 30 미만: 과매도', 2)
    _row("MFI(14)", f'{tech["volume"]["mfi_14"]}  ← 20 미만: 자금 유입 약함', 2)
    _row("MACD", tech["trend"]["macd_state"], 2)
    _row("볼린저밴드", tech["volatility"]["bollinger_position"], 2)

    print("\n  [펀더멘털]")
    _row("PER / PBR", f'{fund["valuation"]["per"]} / {fund["valuation"]["pbr"]}', 2)
    _row("ROE", f'{fund["profitability"]["roe"]}%', 2)
    _row("밸류에이션", fund["valuation"]["status"], 2)

    print("\n  [감성]")
    _row("감성 점수", f'{sent["daily_score"]}  (긍정 {sent["pos_prob"]:.0%} / 부정 {sent["neg_prob"]:.0%})', 2)

    print("\n  [포트폴리오]")
    _row("현금", f'{DUMMY_PORTFOLIO_SNAPSHOT["cash_balance"]:,}원', 2)
    _row("총자산", f'{DUMMY_PORTFOLIO_SNAPSHOT["total_assets"]:,}원', 2)
    _row("보유 종목", "없음", 2)

    print("\n  [사용자 컨텍스트 (더미)]")
    _row("투자 성향", DUMMY_CONTEXT["user_context"]["investor_type"]["risk_grade"], 2)
    _row("자동매매 허용", DUMMY_CONTEXT["policy_context"]["allow_auto_trade"], 2)
    _row("최대 주문금액", f'{DUMMY_CONTEXT["policy_context"]["max_order_amount"]:,}원', 2)


# ── 노드별 출력 ────────────────────────────────────────────────────────────

STEP_LABELS = {
    "context_loader":   "context_loader   — 컨텍스트 로드",
    "bull_researcher":  "bull_researcher  — 매수 논거 생성",
    "bear_researcher":  "bear_researcher  — 매도 논거 생성",
    "strategy_manager": "strategy_manager — 토론 종합 / 판결",
    "decision_manager": "decision_manager — 최종 투자 결정",
    "risk_gate":        "risk_gate        — 리스크 게이트",
}


def _print_node(step: int, name: str, output: dict) -> None:
    label = STEP_LABELS.get(name, name)
    print(f"\n  [{step}] {label}")

    if name == "context_loader":
        policy = output.get("policy_context", {})
        user = output.get("user_context", {})
        _row("투자 성향", user.get("investor_type", {}).get("risk_grade", "-"), 3)
        _row("자동매매 허용", policy.get("allow_auto_trade", "-"), 3)
        _row("메모리 요약", output.get("memory_context", {}).get("summary", "-"), 3)

    elif name == "bull_researcher":
        debate = output.get("investment_debate_state", {})
        text = debate.get("bull_history", "").replace("Bull Analyst:", "").strip()
        print(f"      {_truncate(text)}")

    elif name == "bear_researcher":
        debate = output.get("investment_debate_state", {})
        text = debate.get("bear_history", "").replace("Bear Analyst:", "").strip()
        print(f"      {_truncate(text)}")

    elif name == "strategy_manager":
        verdict = output.get("research_verdict")
        if verdict:
            v = verdict if isinstance(verdict, dict) else verdict.model_dump()
            _row("우세 측", v.get("winning_side", "-"), 3)
            _row("추천 방향", v.get("recommended_side", "-"), 3)
            _row("신뢰도", v.get("confidence", "-"), 3)
            _row("근거 요약", _truncate(v.get("rationale", ""), 100), 3)
            if v.get("recommended_side") != "hold":
                _row("주문금액", f'{v.get("order_amount", 0):,}원', 3)
                _row("목표가", v.get("target_price", "-"), 3)
                _row("손절가", v.get("stop_loss_price", "-"), 3)

    elif name == "decision_manager":
        decision = output.get("final_decision")
        if decision:
            d = decision if isinstance(decision, dict) else decision.model_dump()
            _row("action", d.get("action", "-"), 3)
            _row("side", d.get("side") or "-", 3)
            amt = d.get("order_amount")
            _row("주문금액", f'{amt:,}원' if amt else "-", 3)
            _row("목표가", d.get("target_price") or "-", 3)
            _row("손절가", d.get("stop_loss_price") or "-", 3)
            _row("리스크 등급", d.get("risk_level", "-"), 3)
            _row("신뢰도", d.get("confidence", "-"), 3)
            _row("사용자 메시지", d.get("user_message", "-"), 3)
        flow = output.get("flow_status")
        if flow:
            _row("→ flow_status", flow, 3)

    elif name == "risk_gate":
        result = output.get("risk_check_result", {})
        _row("status", result.get("status", "-"), 3)
        _row("reason", result.get("reason", "-"), 3)
        _row("risk_cleared", output.get("risk_cleared", False), 3)
        for c in result.get("checks", []):
            mark = "✓" if c["status"] == "passed" else "✗"
            print(f"        {mark} {c['name']}: {c['reason']}")


# ── 최종 결과 요약 ─────────────────────────────────────────────────────────

def _print_summary(state: dict) -> None:
    _section("최종 결과 요약")

    flow = state.get("flow_status", "-")
    status_label = {
        "completed": "✓ completed — Risk Gate 통과, 주문 실행 가능",
        "hold":      "○ hold      — 에이전트가 관망 결정 (주문 없음)",
        "blocked":   "✗ blocked   — Risk Gate 차단",
        "failed":    "✗ failed    — 파이프라인 오류",
    }
    print(f"  flow_status : {status_label.get(flow, flow)}")

    decision = state.get("final_decision")
    if decision:
        d = decision if isinstance(decision, dict) else decision.model_dump()
        print()
        if d.get("action") == "trade":
            side_kr = {"buy": "매수 ▲", "sell": "매도 ▼"}.get(d.get("side", ""), d.get("side"))
            _row("종목", f'{d.get("asset", "-")} (삼성전자)', 2)
            _row("방향", side_kr, 2)
            amt = d.get("order_amount")
            _row("주문금액", f'{amt:,}원' if amt else "-", 2)
            _row("목표가", d.get("target_price") or "-", 2)
            _row("손절가", d.get("stop_loss_price") or "-", 2)
            _row("리스크 등급", d.get("risk_level", "-"), 2)
        else:
            print("  action : hold — 주문 없음")

    verdict = state.get("research_verdict")
    if verdict:
        v = verdict if isinstance(verdict, dict) else verdict.model_dump()
        print()
        print("  [strategy_manager 판결]")
        _row("우세 측", v.get("winning_side"), 2)
        bulls = v.get("key_bull_points", [])[:2]
        bears = v.get("key_bear_points", [])[:2]
        for b in bulls:
            print(f"    bull ▲  {b}")
        for b in bears:
            print(f"    bear ▼  {b}")
    print()


# ── 더미 context_loader ────────────────────────────────────────────────────

def dummy_context_loader(state) -> dict:
    print(f"      [더미] DB 없이 하드코딩된 컨텍스트 반환 (user_id={state.user_id})")
    return DUMMY_CONTEXT


# ── 메인 ──────────────────────────────────────────────────────────────────

def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    # local_tests/ 안에 있으므로 부모(ai_agent/)를 path에 추가해 app.* import 가능하게 한다
    sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
    os.environ.setdefault("LANGCHAIN_PROJECT", "modu-mvp")
    os.environ.setdefault("DATABASE_URL", "postgresql://dummy:dummy@localhost/dummy")

    import app.graph.builder as builder_module
    from app.state.investment_state import InvestmentAgentState

    # build_investment_graph() 호출 전에 교체해야 graph.add_node()에 더미가 등록됩니다.
    builder_module.context_loader = dummy_context_loader

    graph = builder_module.build_investment_graph()

    initial_state = InvestmentAgentState(
        user_id=1,
        analysis_snapshot=DUMMY_ANALYSIS_SNAPSHOT,
        candidate_assets=[
            {"stock_code": "005930", "name": "삼성전자", "sector": "semiconductor"}
        ],
        portfolio_snapshot=DUMMY_PORTFOLIO_SNAPSHOT,
    )

    tracing = os.getenv("LANGCHAIN_TRACING_V2", "false")
    _section(f"AI Agent 더미 파이프라인  |  LangSmith: {tracing}")
    _row("종목", f'{DUMMY_ANALYSIS_SNAPSHOT["stock_code"]} (삼성전자)', 1)
    _row("LangSmith 프로젝트", os.getenv("LANGCHAIN_PROJECT", "-"), 1)

    _print_input()

    _section("파이프라인 실행")

    step = 0
    final_state = None
    for mode, event in graph.stream(initial_state, stream_mode=["updates", "values"]):
        if mode == "updates":
            for node_name, node_output in event.items():
                step += 1
                _print_node(step, node_name, node_output)
        else:
            final_state = event

    _print_summary(final_state)


if __name__ == "__main__":
    main()
