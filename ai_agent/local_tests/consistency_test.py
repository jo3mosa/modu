"""
일관성 테스트 — temperature 설정에 따른 최종 결정 분포 측정

실행 방법 (ai_agent/ 디렉토리에서):
    # 단일 실행 (시나리오 + temp 직접 지정)
    PYTHONUTF8=1 conda run -n modu-ai python local_tests/consistency_test.py \\
        --scenario buy --runs 10 --debate-temp 0.2 --decision-temp 0.0

    # 1단계 전체 자동 실행 (debate=0.2 고정, decision=[0.0, 0.1, 0.2] 순서로)
    PYTHONUTF8=1 conda run -n modu-ai python local_tests/consistency_test.py \\
        --scenario buy --runs 10 --phase 1

    # 2단계 전체 자동 실행 (decision=best 고정, debate=[0.2, 0.4, 0.7] 순서로)
    PYTHONUTF8=1 conda run -n modu-ai python local_tests/consistency_test.py \\
        --scenario buy --runs 10 --phase 2 --best-decision-temp 0.0

필요한 .env 설정:
    GMS_KEY=<SSAFY GMS 키>
    LANGCHAIN_TRACING_V2=true  # LangSmith 사용 시
"""
import argparse
import os
import sys
from collections import Counter
from typing import Any


# ── 시나리오 더미 데이터 ────────────────────────────────────────────────────

# 시나리오별 공통 포트폴리오 (신호 자체를 격리하기 위해 동일하게 유지)
_PORTFOLIO = {
    "cash_balance": 3_000_000,
    "total_assets": 5_000_000,
    "current_price": 71_000,
    "holdings": [
        {
            "stock_code": "005930",
            "stock_name": "삼성전자",
            "quantity": 10,
            "average_price": 70_000,
        }
    ],
}

# 시나리오별 공통 컨텍스트 (local_test.py와 동일)
_CONTEXT = {
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
        "allow_auto_trade": True,
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
            "key_signals": [],
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

SCENARIOS: dict[str, dict] = {
    # RSI 과매도 + 전방위 매수 신호
    "buy": {
        "stock_code": "005930",
        "timestamp": "2026-05-16T08:00:00Z",
        "signals": {
            "technical": {
                "trend": {
                    "sma_alignment": "bullish_aligned",
                    "macd_state": "bullish_cross",
                },
                "momentum": {"rsi_14": 27.0},
                "volatility": {
                    "bollinger_position": "lower_breakout",
                    "atr_ratio": 0.025,
                },
                "volume": {"mfi_14": 16.0},
            },
            "fundamental": {
                "valuation": {"per": 10.2, "pbr": 0.9, "status": "undervalued"},
                "profitability": {"roe": 18.5, "status": "high_margin"},
                "growth": {"status": "steady_growth"},
                "stability": {"status": "stable"},
            },
            "event": {
                "has_urgent_issue": False,
                "recent_disclosures": [],
            },
            "sentiment": {
                "daily_score": 0.72,
                "confidence_level": "high",
                "pos_prob": 0.75,
                "neu_prob": 0.18,
                "neg_prob": 0.07,
            },
        },
    },

    # RSI 과매수 + 전방위 매도 신호
    "sell": {
        "stock_code": "005930",
        "timestamp": "2026-05-16T08:00:00Z",
        "signals": {
            "technical": {
                "trend": {
                    "sma_alignment": "bearish_aligned",
                    "macd_state": "bearish_cross",
                },
                "momentum": {"rsi_14": 83.0},
                "volatility": {
                    "bollinger_position": "upper_breakout",
                    "atr_ratio": 0.038,
                },
                "volume": {"mfi_14": 79.0},
            },
            "fundamental": {
                "valuation": {"per": 28.5, "pbr": 2.8, "status": "overvalued"},
                "profitability": {"roe": 7.2, "status": "low_margin"},
                "growth": {"status": "declining"},
                "stability": {"status": "unstable"},
            },
            "event": {
                "has_urgent_issue": True,
                "recent_disclosures": [
                    {
                        "time": "09:30",
                        "title": "영업손실 전환 공시",
                        "impact_level": "negative",
                    }
                ],
            },
            "sentiment": {
                "daily_score": -0.48,
                "confidence_level": "high",
                "pos_prob": 0.12,
                "neu_prob": 0.22,
                "neg_prob": 0.66,
            },
        },
    },

    # 혼재 신호 — 명확한 방향 없음
    "neutral": {
        "stock_code": "005930",
        "timestamp": "2026-05-16T08:00:00Z",
        "signals": {
            "technical": {
                "trend": {
                    "sma_alignment": "mixed",
                    "macd_state": "flat",
                },
                "momentum": {"rsi_14": 51.0},
                "volatility": {
                    "bollinger_position": "middle",
                    "atr_ratio": 0.015,
                },
                "volume": {"mfi_14": 50.0},
            },
            "fundamental": {
                "valuation": {"per": 16.0, "pbr": 1.5, "status": "fairly_valued"},
                "profitability": {"roe": 11.0, "status": "average"},
                "growth": {"status": "stable"},
                "stability": {"status": "stable"},
            },
            "event": {
                "has_urgent_issue": False,
                "recent_disclosures": [],
            },
            "sentiment": {
                "daily_score": 0.05,
                "confidence_level": "low",
                "pos_prob": 0.35,
                "neu_prob": 0.40,
                "neg_prob": 0.25,
            },
        },
    },
}


# ── 부트스트랩 ─────────────────────────────────────────────────────────────

def _bootstrap() -> None:
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass
    parent_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    if parent_dir not in sys.path:
        sys.path.insert(0, parent_dir)
    os.environ.setdefault("LANGCHAIN_PROJECT", "modu-mvp-consistency")
    os.environ.setdefault("DATABASE_URL", "postgresql://dummy:dummy@localhost/dummy")


# ── 온도 설정 ──────────────────────────────────────────────────────────────

def _set_temperatures(debate: float, decision: float) -> None:
    """
    env var 교체만으로 다음 LLM 호출부터 새 temperature가 적용된다.
    _build_llm은 temperature를 인자로 받아 캐시하므로 값이 바뀌면
    자동으로 새 인스턴스를 생성한다 — 캐시 클리어 불필요.
    """
    os.environ["DEBATE_TEMPERATURE"] = str(debate)
    os.environ["DECISION_TEMPERATURE"] = str(decision)


# ── 파이프라인 단일 실행 ───────────────────────────────────────────────────

def _run_once(scenario_snapshot: dict, debug: bool = False) -> dict[str, Any]:
    import app.graph.builder as builder_module
    from app.state.investment_state import InvestmentAgentState

    builder_module.context_loader = lambda state: _CONTEXT

    graph = builder_module.build_investment_graph()

    initial_state = InvestmentAgentState(
        user_id=1,
        analysis_snapshot=scenario_snapshot,
        candidate_assets=[
            {"stock_code": "005930", "name": "삼성전자", "sector": "semiconductor"}
        ],
        portfolio_snapshot=_PORTFOLIO,
    )

    try:
        final_state: dict[str, Any] = {}
        node_outputs: dict[str, Any] = {}

        for mode, event in graph.stream(initial_state, stream_mode=["updates", "values"]):
            if mode == "updates":
                node_outputs.update(event)
            elif mode == "values":
                final_state = event

        if debug:
            _print_debug(node_outputs, final_state)

        decision = final_state.get("final_decision")
        error_ctx = final_state.get("error_context") or {}

        if decision is None:
            return {"action": "error", "side": None, "parse_failed": True, "reason": "final_decision 없음"}

        d = decision if isinstance(decision, dict) else decision.model_dump()
        action = d.get("action", "unknown")
        side = d.get("side")
        reason = d.get("reason_summary", "")

        parse_failed = "파싱" in reason or bool(error_ctx)

        return {
            "action": action,
            "side": side,
            "parse_failed": parse_failed,
            "reason": reason,
        }

    except Exception as exc:
        return {"action": "error", "side": None, "parse_failed": True, "reason": str(exc)}


def _print_debug(node_outputs: dict[str, Any], final_state: dict[str, Any]) -> None:
    """각 에이전트의 중간 출력을 단계별로 출력한다."""
    DSEP = "=" * 72
    SSEP = "-" * 72

    print(f"\n{DSEP}")
    print("  [DEBUG] 에이전트 단계별 출력")
    print(DSEP)

    # ── Bull Researcher ──────────────────────────────────────────────────
    bull_out = node_outputs.get("bull_researcher", {})
    debate_after_bull = bull_out.get("investment_debate_state", {})
    bull_history = debate_after_bull.get("bull_history", [])
    bull_text = bull_history[-1] if bull_history else "(출력 없음)"
    print(f"\n[1] Bull Researcher")
    print(SSEP)
    print(bull_text)

    # ── Bear Researcher ──────────────────────────────────────────────────
    bear_out = node_outputs.get("bear_researcher", {})
    debate_after_bear = bear_out.get("investment_debate_state", {})
    bear_history = debate_after_bear.get("bear_history", [])
    bear_text = bear_history[-1] if bear_history else "(출력 없음)"
    print(f"\n[2] Bear Researcher")
    print(SSEP)
    print(bear_text)

    # ── Strategy Manager ─────────────────────────────────────────────────
    strategy_out = node_outputs.get("strategy_manager", {})
    verdict = strategy_out.get("research_verdict")
    print(f"\n[3] Strategy Manager -> ResearchVerdict")
    print(SSEP)
    if verdict is not None:
        v = verdict if isinstance(verdict, dict) else verdict.model_dump()
        print(f"  winning_side      : {v.get('winning_side')}")
        print(f"  recommended_side  : {v.get('recommended_side')}")
        print(f"  asset             : {v.get('asset')}")
        print(f"  confidence        : {v.get('confidence')}")
        print(f"  order_amount      : {v.get('order_amount')}")
        print(f"  target_price      : {v.get('target_price')}")
        print(f"  stop_loss_price   : {v.get('stop_loss_price')}")
        print(f"  rationale         :")
        for line in (v.get('rationale') or "").splitlines():
            print(f"    {line}")
    else:
        print("  (research_verdict 없음 - hold 강등)")
    if strategy_out.get("error_context"):
        print(f"  error_context : {strategy_out['error_context']}")

    # ── Decision Manager ─────────────────────────────────────────────────
    decision_out = node_outputs.get("decision_manager", {})
    decision = decision_out.get("final_decision")
    print(f"\n[4] Decision Manager -> FinalDecision")
    print(SSEP)
    if decision is not None:
        d = decision if isinstance(decision, dict) else decision.model_dump()
        print(f"  action            : {d.get('action')}")
        print(f"  side              : {d.get('side')}")
        print(f"  asset             : {d.get('asset')}")
        print(f"  risk_level        : {d.get('risk_level')}")
        print(f"  confidence        : {d.get('confidence')}")
        print(f"  order_amount      : {d.get('order_amount')}")
        print(f"  target_price      : {d.get('target_price')}")
        print(f"  stop_loss_price   : {d.get('stop_loss_price')}")
        print(f"  reason_summary    : {d.get('reason_summary')}")
        print(f"  user_message      : {d.get('user_message')}")
        scen = d.get("expected_scenario") or {}
        if isinstance(scen, dict):
            print(f"  scenario.base     : {scen.get('base')}")
            print(f"  scenario.bear     : {scen.get('bear')}")
            print(f"  scenario.bull     : {scen.get('bull')}")
    else:
        print("  (final_decision 없음)")

    # ── Risk Gate ────────────────────────────────────────────────────────
    risk_out = node_outputs.get("risk_gate", {})
    if risk_out:
        print(f"\n[5] Risk Gate")
        print(SSEP)
        rg_decision = risk_out.get("final_decision")
        if rg_decision is not None:
            d = rg_decision if isinstance(rg_decision, dict) else rg_decision.model_dump()
            print(f"  action (after gate): {d.get('action')}")
            print(f"  side               : {d.get('side')}")
            print(f"  reason_summary     : {d.get('reason_summary')}")
        rg_err = risk_out.get("error_context")
        if rg_err:
            print(f"  error_context      : {rg_err}")

    # ── flow_status ──────────────────────────────────────────────────────
    print(f"\n  flow_status : {final_state.get('flow_status')}")
    err_ctx = final_state.get("error_context")
    if err_ctx:
        print(f"  error_context : {err_ctx}")
    print(DSEP)


# ── 결과 집계 및 출력 ──────────────────────────────────────────────────────

SEP = "-" * 64

def _result_key(r: dict) -> str:
    if r["parse_failed"]:
        return "parse_fail"
    if r["action"] == "hold":
        return "hold"
    return r["side"] or r["action"]


def _print_result_table(
    label: str,
    scenario: str,
    debate_temp: float,
    decision_temp: float,
    results: list[dict],
) -> None:
    n = len(results)
    counts = Counter(_result_key(r) for r in results)
    parse_fails = sum(1 for r in results if r["parse_failed"])
    buy_sell_flips = sum(
        1 for i in range(1, n)
        if {_result_key(results[i - 1]), _result_key(results[i])} == {"buy", "sell"}
    )

    dominant = counts.most_common(1)[0] if counts else ("?", 0)
    consistency_pct = dominant[1] / n * 100 if n else 0
    pass_mark = "PASS" if dominant[1] >= int(n * 0.8) else "FAIL"

    print(f"\n{SEP}")
    print(f"  {label}")
    print(f"  시나리오: {scenario}  |  debate={debate_temp}  decision={decision_temp}  |  {n}회 실행")
    print(SEP)
    print(f"  결정 분포:")
    for key in ["buy", "sell", "hold", "parse_fail"]:
        if counts[key]:
            bar = "#" * counts[key]
            print(f"    {key:<12} {counts[key]:>2}회  {bar}")
    print()
    print(f"  주요 결정    : {dominant[0]} ({dominant[1]}/{n}회, {consistency_pct:.0f}%)")
    print(f"  일관성 판정  : {pass_mark}  {'통과 (>=80%)' if pass_mark == 'PASS' else '미달 (<80%)'}")
    print(f"  파싱 실패    : {parse_fails}회  {'OK' if parse_fails == 0 else 'WARNING'}")
    print(f"  buy↔sell 전환: {buy_sell_flips}회")
    print(SEP)


# ── 실험 실행 ─────────────────────────────────────────────────────────────

def run_single(scenario: str, n_runs: int, debate_temp: float, decision_temp: float, debug: bool = False) -> list[dict]:
    _set_temperatures(debate_temp, decision_temp)
    snapshot = SCENARIOS[scenario]
    results = []

    print(f"\n  실행 중... (debate={debate_temp}, decision={decision_temp})")
    for i in range(n_runs):
        r = _run_once(snapshot, debug=debug)
        key = _result_key(r)
        print(f"    [{i+1:>2}/{n_runs}]  {key}  --  {r.get('reason', '')[:60]}")
        results.append(r)

    return results


def run_phase1(scenario: str, n_runs: int, debug: bool = False) -> None:
    """1단계: debate=0.2 고정, decision=[0.0, 0.1, 0.2] 변화"""
    print(f"\n{'=' * 64}")
    print(f"  1단계 실험  -  decision temperature 영향 확인")
    print(f"  고정: debate=0.2  |  변수: decision=[0.0, 0.1, 0.2]")
    print(f"{'=' * 64}")

    for d_temp in [0.0, 0.1, 0.2]:
        results = run_single(scenario, n_runs, debate_temp=0.2, decision_temp=d_temp, debug=debug)
        _print_result_table(
            label=f"[1단계] decision_temp={d_temp}",
            scenario=scenario,
            debate_temp=0.2,
            decision_temp=d_temp,
            results=results,
        )


def run_phase2(scenario: str, n_runs: int, best_decision_temp: float, debug: bool = False) -> None:
    """2단계: decision=best 고정, debate=[0.2, 0.4, 0.7] 변화"""
    print(f"\n{'=' * 64}")
    print(f"  2단계 실험  -  debate temperature 영향 확인")
    print(f"  고정: decision={best_decision_temp}  |  변수: debate=[0.2, 0.4, 0.7]")
    print(f"{'=' * 64}")

    for db_temp in [0.2, 0.4, 0.7]:
        results = run_single(scenario, n_runs, debate_temp=db_temp, decision_temp=best_decision_temp, debug=debug)
        _print_result_table(
            label=f"[2단계] debate_temp={db_temp}",
            scenario=scenario,
            debate_temp=db_temp,
            decision_temp=best_decision_temp,
            results=results,
        )


# ── 진입점 ─────────────────────────────────────────────────────────────────

def main() -> None:
    _bootstrap()

    parser = argparse.ArgumentParser(description="AI 에이전트 일관성 테스트")
    parser.add_argument("--scenario", choices=["buy", "sell", "neutral"], default="buy",
                        help="테스트 시나리오 (기본: buy)")
    parser.add_argument("--runs", type=int, default=10,
                        help="시나리오당 반복 실행 횟수 (기본: 10)")
    parser.add_argument("--phase", type=int, choices=[1, 2],
                        help="1: decision temp 실험, 2: debate temp 실험")
    parser.add_argument("--debate-temp", type=float, default=0.2,
                        help="Bull/Bear LLM temperature (기본: 0.2)")
    parser.add_argument("--decision-temp", type=float, default=0.2,
                        help="Strategy/Decision LLM temperature (기본: 0.2)")
    parser.add_argument("--best-decision-temp", type=float, default=0.0,
                        help="2단계 실험 시 고정할 decision temperature (기본: 0.0)")
    parser.add_argument("--debug", action="store_true",
                        help="각 에이전트의 중간 출력을 단계별로 출력 (단일 실행 분석용)")
    args = parser.parse_args()

    print(f"\n{'=' * 64}")
    print(f"  AI 에이전트 일관성 테스트")
    print(f"  시나리오: {args.scenario}  |  반복: {args.runs}회")
    if args.debug:
        print(f"  [DEBUG 모드 - 에이전트 단계별 출력 활성화]")
    print(f"{'=' * 64}")

    if args.phase == 1:
        run_phase1(args.scenario, args.runs, debug=args.debug)
    elif args.phase == 2:
        run_phase2(args.scenario, args.runs, args.best_decision_temp, debug=args.debug)
    else:
        results = run_single(args.scenario, args.runs, args.debate_temp, args.decision_temp, debug=args.debug)
        _print_result_table(
            label="단일 실행 결과",
            scenario=args.scenario,
            debate_temp=args.debate_temp,
            decision_temp=args.decision_temp,
            results=results,
        )


if __name__ == "__main__":
    main()
