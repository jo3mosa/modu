"""backtest_to_validation_csv

backtest_out_*/triggers_*.jsonl 들을 validate_news_window.py 가 먹는 CSV 로 변환.

backtest framework 가 실제 historical 데이터로 detection_engine 을 돌려 만든
트리거 발화 기록 — 휴리스틱 picking 대신 진짜 발화 사건으로 검증하면 트리거-뉴스
컨텍스트 일치성이 보장된다.

(stock_code, as_of_date) 같은 날 같은 종목에 여러 행이 있으면 rule_ids 합집합으로
dedup — 한 시점 한 종목에 대한 단일 검증 샘플로.

사용법:
    python -m scripts.backfill.backtest_to_validation_csv \
        --inputs backtest_out_public backtest_out_postgres backtest_out_smoke \
        --out scripts/backfill/samples_backtest.csv

NOTE: 출력 CSV 의 종목은 backtest universe (예: 060310, 211270) — 우리 portfolio
      5종목과 겹치지 않을 수 있다. context_loader 가 portfolio 없는 종목에 어떻게
      반응하는지는 정식 검증 전 dry-run 으로 확인 필요.
"""

from __future__ import annotations

import argparse
import csv
import json
import logging
import sys
from collections import defaultdict
from pathlib import Path

_REPO_ROOT = Path(__file__).resolve().parents[2]
_MODU_ROOT = Path(__file__).resolve().parents[3]
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

logger = logging.getLogger(__name__)


def collect_triggers(input_dirs: list[Path]) -> list[dict]:
    """입력 폴더들에서 triggers_*.jsonl 모두 읽어 raw dict 리스트로.

    한 줄 파싱 실패는 skip + warning — 손상된 라인 1건이 전체 변환을 중단시키지
    않도록 내결함성 확보 (대량 백필 안정성).
    """
    rows: list[dict] = []
    skipped = 0
    for d in input_dirs:
        abs_d = d if d.is_absolute() else (_MODU_ROOT / d)
        if not abs_d.exists():
            logger.warning("폴더 없음 (skip): %s", abs_d)
            continue
        for p in sorted(abs_d.glob("triggers_*.jsonl")):
            for line_no, line in enumerate(p.read_text(encoding="utf-8").splitlines(), 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    rows.append(json.loads(line))
                except json.JSONDecodeError as e:
                    logger.warning("JSON 파싱 실패 %s:%d (skip): %s", p.name, line_no, e)
                    skipped += 1
            logger.info("  loaded %s", p.name)
    if skipped:
        logger.warning("총 %d 라인 파싱 실패 skip — 데이터 손상 확인 권장", skipped)
    return rows


def dedup_by_stock_date(rows: list[dict]) -> list[dict]:
    """(stock_code, as_of_date) 단위로 rule_ids 합집합 — 같은 날 같은 종목 1개 샘플."""
    bucket: dict[tuple[str, str], set[str]] = defaultdict(set)
    for r in rows:
        key = (r["stock_code"], r["as_of_date"])
        bucket[key].update(r.get("rule_ids") or [])
    return [
        {"stock_code": s, "as_of_date": d, "rule_ids": sorted(rules)}
        for (s, d), rules in sorted(bucket.items())
        if rules
    ]


def to_csv_rows(samples: list[dict], hour: int) -> list[tuple[str, str, str]]:
    """샘플 → CSV row tuple. timestamp 는 as_of_date + hour:00:00 KST."""
    out: list[tuple[str, str, str]] = []
    for s in samples:
        ts = f"{s['as_of_date']}T{hour:02d}:00:00+09:00"
        out.append((s["stock_code"], ts, ";".join(s["rule_ids"])))
    return out


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
        datefmt="%H:%M:%S",
    )
    p = argparse.ArgumentParser(description="backtest_out 트리거 → validation CSV 변환")
    p.add_argument("--inputs", nargs="+", required=True,
                   help="입력 폴더들 (modu repo 루트 기준 상대 경로). "
                        "예: backtest_out_public backtest_out_postgres backtest_out_smoke")
    p.add_argument("--out", required=True, type=Path,
                   help="출력 CSV 경로. 예: scripts/backfill/samples_backtest.csv")
    p.add_argument("--hour", type=int, default=9, choices=range(0, 24),
                   metavar="{0-23}",
                   help="timestamp 시각 (KST). 기본 9.")
    p.add_argument("--filter-stocks", default=None,
                   help="콤마 구분 종목 코드. 지정 시 해당 종목만. 예: '005930,035420'")
    args = p.parse_args()

    raw = collect_triggers([Path(d) for d in args.inputs])
    logger.info("총 raw 트리거: %d 건", len(raw))

    samples = dedup_by_stock_date(raw)
    logger.info("dedup 후 (stock,date) 샘플: %d 건", len(samples))

    if args.filter_stocks:
        whitelist = {s.strip() for s in args.filter_stocks.split(",") if s.strip()}
        before = len(samples)
        samples = [s for s in samples if s["stock_code"] in whitelist]
        logger.info("종목 필터 (%s): %d → %d 건", sorted(whitelist), before, len(samples))

    if not samples:
        sys.stderr.write("[ERROR] 샘플 0건 — 입력 폴더/필터 확인\n")
        sys.exit(2)

    rows = to_csv_rows(samples, args.hour)
    args.out.parent.mkdir(parents=True, exist_ok=True)   # 상위 디렉터리 자동 생성
    with args.out.open("w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["stock_code", "timestamp", "rule_ids"])
        w.writerows(rows)

    logger.info("✓ %d 샘플 저장: %s", len(rows), args.out)
    # 종목 분포 요약
    from collections import Counter
    stock_dist = Counter(s["stock_code"] for s in samples)
    logger.info("종목 분포: %s", stock_dist.most_common())


if __name__ == "__main__":
    main()
