"""smoke_test_news_summary

뉴스 요약 파이프라인 end-to-end 점검 — MongoDB 조회 + Haiku 요약 + payload 형태 확인.

특정 종목 + 시점을 인자로 받아 _summarize_news 의 출력을 그대로 출력한다.
None 이 나오면 (a) 기사 0건 또는 (b) LLM 실패 — 로그에 사유 노출.

사용법:
    python -m scripts.backfill.smoke_test_news_summary --stock 005930
    python -m scripts.backfill.smoke_test_news_summary --stock 000660 --rule DART-001
    python -m scripts.backfill.smoke_test_news_summary --stock 005930 --rule RSI-001 --at 2026-05-15T14:00:00+09:00
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

_REPO_ROOT = Path(__file__).resolve().parents[2]
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

from engine.event_publisher import _summarize_news      # noqa: E402
from engine.signal_builder import Signal                # noqa: E402

KST = ZoneInfo("Asia/Seoul")


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
        datefmt="%H:%M:%S",
    )
    p = argparse.ArgumentParser(description="뉴스 요약 smoke test")
    p.add_argument("--stock", required=True, help="종목 6자리. 예: 005930 (삼성전자)")
    p.add_argument("--rule", default="RSI-001",
                   help="발화 시뮬레이션 rule_id. 기본 RSI-001 (7일 윈도우). "
                        "다른 윈도우 보려면 DART-001(24h), MACD-001(14d) 등.")
    p.add_argument("--at", default=None,
                   help="트리거 시점 ISO datetime (KST 권장). 기본 = 지금.")
    args = p.parse_args()

    anchor = datetime.fromisoformat(args.at) if args.at else datetime.now(KST)
    if anchor.tzinfo is None:
        anchor = anchor.replace(tzinfo=KST)

    fake_signal = Signal(stock_code=args.stock, timestamp=anchor, signals={})
    print(f"\n▶ stock={args.stock} rule={args.rule} anchor={anchor.isoformat()}\n")

    result = _summarize_news(args.stock, [args.rule], fake_signal)
    if result is None:
        print("결과: None (기사 0건 또는 LLM 실패) — 위 로그 확인")
        return

    print("─" * 70)
    print("[ window ]      ", result["window"])
    print("[ article_count ]", result["article_count"])
    print("─" * 70)
    print("[ summary ]")
    print(result.get("summary") or "(LLM 실패 — summary 없음)")
    print("─" * 70)
    print("[ top_articles ]")
    print(json.dumps(result["top_articles"], ensure_ascii=False, indent=2, default=str))
    print("─" * 70)


if __name__ == "__main__":
    main()
