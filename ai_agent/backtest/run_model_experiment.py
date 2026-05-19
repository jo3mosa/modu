"""LLM 모델 비교 실험 오케스트레이터.

autoresearch 패턴: 같은 데이터·같은 모드로 모델 variant를 자동 순회 실행 → 결과 비교.
모든 실험은 N=1 (debate_1) 고정, risk_tier=neutral 고정 상태에서 모델 변수만 변경.

사용:
    # 전체 실험 실행
    python -m ai_agent.backtest.run_model_experiment \\
        --watchlist 005930,000660,035720,105560,035420 \\
        --start 2024-01-02 --end 2024-01-31

    # 특정 실험만
    python -m ai_agent.backtest.run_model_experiment \\
        --experiments gpt_mini,claude,grok

    # 실험은 이미 돌렸고 비교 테이블만 다시 출력
    python -m ai_agent.backtest.run_model_experiment --report-only

실험 목록:
    ── Axis 1: 단일 모델 비교 ──────────────────────────────
    gpt_mini               전 노드 gpt-4o-mini (저비용 baseline)
    gpt_full               전 노드 gpt-4o     (고성능 baseline)
    claude                 전 노드 claude-3-5-sonnet-20241022
    grok                   전 노드 grok-2

    ── Axis 2: 모델 분리, GPT 계열 (비용 최적화) ────────────
    split_c                bull/bear=mini, strategy+decision=full  (판단이 중요하다)
    split_d                bull/bear=full, strategy+decision=mini  (근거가 중요하다)

    ── Axis 3: 크로스 모델 (각 모델 강점 포지션 활용) ────────
    claude_debate_gpt_judge  bull/bear=claude, strategy+decision=gpt-4o
    grok_debate_gpt_judge    bull/bear=grok-2, strategy+decision=gpt-4o
    best_combo               bull/bear=claude, strategy=claude, decision=gpt-4o

환경변수 우선순위 (llm.py 참고):
    DEBATE_MODEL   → bull/bear 노드
    STRATEGY_MODEL → strategy_manager (없으면 STRUCTURED_MODEL 폴백)
    DECISION_MODEL → decision_manager (없으면 STRUCTURED_MODEL 폴백)
    STRUCTURED_MODEL → strategy+decision 공통 폴백

주의:
    estimated_cost_usd는 get_openai_callback 기반 → OpenAI 모델만 유효.
    Claude/Grok은 토큰 수는 기록되나 비용은 0으로 표시됨.
    실제 비용은 각 provider 콘솔에서 확인 필요.
"""
from __future__ import annotations

import argparse
import json
import logging
import os
import subprocess
import sys
from datetime import datetime
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

# ── 실험 정의 ──────────────────────────────────────────────────────────────────

EXPERIMENT_CONFIGS: dict[str, dict[str, str]] = {
    # Axis 1: 단일 모델 비교 (GPT)
    "gpt_mini": {
        "DEBATE_MODEL": "gpt-4o-mini",
        "STRUCTURED_MODEL": "gpt-4o-mini",
    },
    "gpt_full": {
        "DEBATE_MODEL": "gpt-4o",
        "STRUCTURED_MODEL": "gpt-4o",
    },
    # Axis 2: 모델 분리 (비용 최적화, GPT 계열)
    "split_c": {
        "DEBATE_MODEL": "gpt-4o-mini",
        "STRUCTURED_MODEL": "gpt-4o",         # strategy + decision 모두 deep
    },
    "split_d": {
        "DEBATE_MODEL": "gpt-4o",             # bull+bear deep: 근거 품질 투자
        "STRUCTURED_MODEL": "gpt-4o-mini",    # strategy+decision quick: 근거 기반 결정
    },
    # ── Claude / Grok 실험 (API 키 준비 후 주석 해제) ─────────────────────────
    # Axis 1: 단일 모델 비교 (Claude / Grok)
    # "claude": {
    #     "DEBATE_MODEL": "claude-3-5-sonnet-20241022",
    #     "STRUCTURED_MODEL": "claude-3-5-sonnet-20241022",
    # },
    # "grok": {
    #     "DEBATE_MODEL": "grok-2",
    #     "STRUCTURED_MODEL": "grok-2",
    # },
    # Axis 3: 크로스 모델 (각 모델 강점 포지션 활용)
    # "claude_debate_gpt_judge": {
    #     "DEBATE_MODEL":     "claude-3-5-sonnet-20241022",
    #     "STRUCTURED_MODEL": "gpt-4o",
    # },
    # "grok_debate_gpt_judge": {
    #     "DEBATE_MODEL":     "grok-2",
    #     "STRUCTURED_MODEL": "gpt-4o",
    # },
    # "best_combo": {
    #     "DEBATE_MODEL":   "claude-3-5-sonnet-20241022",
    #     "STRATEGY_MODEL": "claude-3-5-sonnet-20241022",
    #     "DECISION_MODEL": "gpt-4o",
    # },
}

# KOSPI200 seed=42 랜덤 10종목
_DEFAULT_WATCHLIST = (
    "005930,000660,035720,105560,035420,"
    "207940,000270,005380,006400,051910"
)
_DEFAULT_START = "2024-01-02"
_DEFAULT_END   = "2024-01-31"


# ── 단일 실험 실행 ──────────────────────────────────────────────────────────────

def _run_one(
    name: str,
    env_overrides: dict[str, str],
    *,
    mode: str,
    start: str,
    end: str,
    watchlist: str,
    output_dir: Path,
    backtest_user_id: int,
    score_after: bool,
    pm_mock: bool,
    initial_holdings: str | None = None,
    resume: bool = False,
) -> bool:
    exp_out = output_dir / name
    exp_out.mkdir(parents=True, exist_ok=True)

    env = os.environ.copy()
    env.update(env_overrides)

    models_desc = " / ".join(f"{k}={v}" for k, v in env_overrides.items())
    logger.info("=== [%s] 시작: %s ===", name, models_desc)

    cmd = [
        sys.executable, "-m", "ai_agent.backtest.run_ai_backtest",
        "--mode", mode,
        "--start", start,
        "--end", end,
        "--watchlist", watchlist,
        "--output", str(exp_out),
        "--backtest-user-id", str(backtest_user_id),
        "--reset-memory",
    ]
    if score_after:
        cmd.append("--score-after")
    if pm_mock:
        cmd.append("--pm-mock")
    if initial_holdings:
        cmd += ["--initial-holdings", initial_holdings]
    if resume:
        cmd.append("--resume")

    ret = subprocess.run(cmd, env=env)
    if ret.returncode != 0:
        logger.error("[%s] 실패: exit=%d", name, ret.returncode)
        return False

    logger.info("=== [%s] 완료 ===", name)
    return True


# ── 결과 집계 ──────────────────────────────────────────────────────────────────

def _aggregate(exp_dir: Path) -> dict[str, Any]:
    """triggers_*.jsonl 또는 scored_*.jsonl 에서 핵심 지표 추출."""
    scored = sorted(exp_dir.glob("scored_*.jsonl"))
    triggers = sorted(exp_dir.glob("triggers_*.jsonl"))
    files = scored if scored else triggers

    if not files:
        return {"error": "no_jsonl"}

    n_total = n_buy = n_sell = n_hold = 0
    n_hit = n_miss = 0
    total_tokens = 0
    total_cost = 0.0
    parse_failures = 0

    for f in files:
        with f.open(encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    rec = json.loads(line)
                except json.JSONDecodeError:
                    continue

                dec = rec.get("decision") or {}
                action = dec.get("action", "hold")
                n_total += 1

                if action == "buy":
                    n_buy += 1
                elif action == "sell":
                    n_sell += 1
                else:
                    n_hold += 1

                extras = dec.get("extras") or {}
                total_tokens += int(extras.get("total_tokens", 0))
                total_cost += float(extras.get("estimated_cost_usd", 0.0))

                # 파싱/그래프 오류로 인한 hold 감지
                reasoning = dec.get("reasoning", "")
                if action == "hold" and any(
                    m in reasoning for m in ("파싱", "graph_decision_fn", "OutputParser")
                ):
                    parse_failures += 1

                # scored JSONL 전용 hit/miss
                hit = rec.get("hit")
                if hit is True:
                    n_hit += 1
                elif hit is False:
                    n_miss += 1

    n_traded = n_buy + n_sell
    hit_rate = n_hit / n_traded if n_traded > 0 else None
    cost_per_dec = total_cost / n_total if n_total > 0 else 0.0
    cost_per_hit = total_cost / n_hit if n_hit > 0 else None

    return {
        "n_total": n_total,
        "n_buy": n_buy,
        "n_sell": n_sell,
        "n_hold": n_hold,
        "n_traded": n_traded,
        "n_hit": n_hit,
        "n_miss": n_miss,
        "hit_rate": hit_rate,
        "parse_failures": parse_failures,
        "parse_failure_rate": parse_failures / n_total if n_total > 0 else 0.0,
        "total_tokens": total_tokens,
        "total_cost_usd": total_cost,
        "cost_per_decision": cost_per_dec,
        "cost_per_hit": cost_per_hit,
        "has_scored": bool(scored),
    }


# ── 비교 테이블 출력 ────────────────────────────────────────────────────────────

def _print_table(results: dict[str, dict], configs: dict[str, dict]) -> None:
    col = {
        "name":     12,
        "models":   48,
        "hit_rate":  9,
        "n_traded":  9,
        "n_hold":    7,
        "pf_rate":  10,
        "cost":      9,
        "cost_hit": 10,
    }
    hdr = (
        f"{'실험':<{col['name']}} {'모델 (debate / structured)':<{col['models']}}"
        f" {'hit_rate':>{col['hit_rate']}} {'n_traded':>{col['n_traded']}}"
        f" {'n_hold':>{col['n_hold']}} {'parse_fail':>{col['pf_rate']}}"
        f" {'cost($)':>{col['cost']}} {'$/hit':>{col['cost_hit']}}"
    )
    sep = "─" * len(hdr)

    print(f"\n{sep}")
    print("LLM 모델 비교 실험 결과")
    print(sep)
    print(hdr)
    print(sep)

    for name, r in results.items():
        if "error" in r:
            print(f"{name:<{col['name']}} [결과 없음: {r['error']}]")
            continue

        cfg = configs.get(name, {})
        debate = cfg.get("DEBATE_MODEL", "?")
        structured = (
            cfg.get("STRATEGY_MODEL", "")
            or cfg.get("STRUCTURED_MODEL", "?")
        )
        # split_d는 strategy/decision이 달라서 표시 구분
        if "STRATEGY_MODEL" in cfg and "DECISION_MODEL" in cfg:
            structured = f"{cfg['STRATEGY_MODEL']}|{cfg['DECISION_MODEL']}"
        models = f"{debate} / {structured}"

        hit_s   = f"{r['hit_rate']:.3f}" if r["hit_rate"] is not None else "N/A (채점 전)"
        cost_s  = f"{r['total_cost_usd']:.4f}"
        cph_s   = f"{r['cost_per_hit']:.5f}" if r["cost_per_hit"] else "N/A"
        pf_s    = f"{r['parse_failure_rate']:.1%}"

        print(
            f"{name:<{col['name']}} {models:<{col['models']}}"
            f" {hit_s:>{col['hit_rate']}} {r['n_traded']:>{col['n_traded']}}"
            f" {r['n_hold']:>{col['n_hold']}} {pf_s:>{col['pf_rate']}}"
            f" {cost_s:>{col['cost']}} {cph_s:>{col['cost_hit']}}"
        )

    print(sep)
    print("※ cost($)·$/hit는 OpenAI 모델만 유효. Claude/Grok 비용은 각 provider 콘솔 확인.")
    print(f"※ hit_rate는 --score-after 실행 후에만 채워집니다.\n")


# ── CLI ────────────────────────────────────────────────────────────────────────

def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    parser = argparse.ArgumentParser(
        description="LLM 모델 비교 실험 오케스트레이터",
        formatter_class=argparse.RawTextHelpFormatter,
    )
    parser.add_argument(
        "--experiments",
        default=",".join(EXPERIMENT_CONFIGS),
        help=(
            "실행할 실험 이름 (쉼표 구분).\n"
            f"기본: 전체. 가능: {', '.join(EXPERIMENT_CONFIGS)}"
        ),
    )
    parser.add_argument("--mode", default="debate_1",
                        help="LangGraph 모드 (기본: debate_1 = N=1 토론 고정)")
    parser.add_argument("--start", default=_DEFAULT_START, help="시작일 YYYY-MM-DD")
    parser.add_argument("--end",   default=_DEFAULT_END,   help="종료일 YYYY-MM-DD")
    parser.add_argument("--watchlist", default=_DEFAULT_WATCHLIST,
                        help="쉼표 구분 종목 코드")
    parser.add_argument(
        "--output", type=Path,
        default=Path(__file__).resolve().parent / "runs" / "model_experiment",
        help="결과 루트 디렉터리 (기본: backtest/runs/model_experiment)",
    )
    parser.add_argument("--backtest-user-id", type=int, default=99998,
                        help="실험 전용 DB user_id — 운영 데이터와 격리 (기본: 99998)")
    parser.add_argument("--score-after", action="store_true",
                        help="각 실험 후 hit_rate scoring 자동 실행")
    parser.add_argument("--pm-mock", action="store_true",
                        help="post_mortem LLM 미호출 (stub). --score-after와 함께 사용")
    parser.add_argument("--report-only", action="store_true",
                        help="실험 재실행 없이 기존 결과로 비교 테이블만 출력")
    parser.add_argument("--initial-holdings", type=str, default=None,
                        help="초기 보유 주식 (예: 005930:100,000660:50). run_ai_backtest에 그대로 전달.")
    parser.add_argument("--resume", action="store_true",
                        help="끊긴 실험 이어서 실행. 각 실험의 run_ai_backtest에 --resume 전달.")
    args = parser.parse_args()

    requested = [e.strip() for e in args.experiments.split(",") if e.strip()]
    unknown = [e for e in requested if e not in EXPERIMENT_CONFIGS]
    if unknown:
        print(f"[ERROR] 알 수 없는 실험: {unknown}", file=sys.stderr)
        print(f"가능한 실험: {list(EXPERIMENT_CONFIGS)}", file=sys.stderr)
        return 1

    args.output.mkdir(parents=True, exist_ok=True)

    if not args.report_only:
        failed = []
        for name in requested:
            ok = _run_one(
                name=name,
                env_overrides=EXPERIMENT_CONFIGS[name],
                mode=args.mode,
                start=args.start,
                end=args.end,
                watchlist=args.watchlist,
                output_dir=args.output,
                backtest_user_id=args.backtest_user_id,
                score_after=args.score_after,
                pm_mock=args.pm_mock,
                initial_holdings=args.initial_holdings,
                resume=args.resume,
            )
            if not ok:
                failed.append(name)
        if failed:
            logger.warning("실패한 실험: %s", failed)

    results = {name: _aggregate(args.output / name) for name in requested}
    _print_table(results, EXPERIMENT_CONFIGS)

    summary_path = args.output / "comparison_summary.json"
    with summary_path.open("w", encoding="utf-8") as f:
        json.dump(
            {
                "run_at": datetime.now().isoformat(),
                "mode": args.mode,
                "start": args.start,
                "end": args.end,
                "watchlist": args.watchlist,
                "experiments": requested,
                "configs": EXPERIMENT_CONFIGS,
                "results": results,
            },
            f,
            ensure_ascii=False,
            indent=2,
            default=str,
        )
    logger.info("비교 결과 저장: %s", summary_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
