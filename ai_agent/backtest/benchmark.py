"""KOSPI/KOSDAQ 벤치마크 가격 조회 — alpha_return 산출용.

`scripts/fetch_kospi.py`가 만든 `data/kospi_daily.csv`를 읽어 PriceFetcher
프로토콜 호환 인터페이스를 제공한다. scoring 모듈에 그대로 주입 가능.

alpha = strategy_return - benchmark_return (같은 holding 구간 기준)
"""
from __future__ import annotations

import csv
import logging
from datetime import date
from pathlib import Path
from typing import Optional

logger = logging.getLogger(__name__)


class KospiBenchmarkFetcher:
    """KOSPI 일별 종가를 PriceFetcher 시그니처로 노출.

    Args:
        csv_path: kospi_daily.csv 경로. 헤더 `date,kospi_close,kosdaq_close` 가정.
        index_key: 'kospi_close' 또는 'kosdaq_close'. 기본 KOSPI.

    Notes:
      - stock_code 인자는 무시 (인덱스 단일 종목 가정). PriceFetcher 시그니처 호환용.
      - 휴장일(KOSPI에 없는 날짜) 조회는 이전 영업일로 backfill — 누락 시 0.0.
    """

    def __init__(self, csv_path: Path, *, index_key: str = "kospi_close") -> None:
        self._csv_path = Path(csv_path)
        self._index_key = index_key
        self._prices: dict[date, float] = {}
        self._sorted_dates: list[date] = []
        self._load()

    def _load(self) -> None:
        if not self._csv_path.exists():
            logger.warning("benchmark CSV 없음 — alpha 산출 비활성: %s", self._csv_path)
            return
        with self._csv_path.open("r", encoding="utf-8") as f:
            reader = csv.DictReader(f)
            for row in reader:
                try:
                    d = date.fromisoformat(row["date"])
                    val = row.get(self._index_key)
                    if val in (None, ""):
                        continue
                    self._prices[d] = float(val)
                except (ValueError, KeyError):
                    continue
        self._sorted_dates = sorted(self._prices.keys())
        logger.info("benchmark loaded: %s (%d 거래일, %s)",
                    self._csv_path.name, len(self._prices), self._index_key)

    def close_price(self, stock_code: str, target_date: date) -> float:
        """target_date 종가. 휴장일이면 이전 영업일 종가."""
        if target_date in self._prices:
            return self._prices[target_date]
        # backfill: target 이하 최근 날짜 — 이분탐색 대신 단순 선형 (CSV 수백~수천 row)
        prev = None
        for d in self._sorted_dates:
            if d > target_date:
                break
            prev = d
        return self._prices.get(prev, 0.0) if prev else 0.0

    def is_loaded(self) -> bool:
        return len(self._prices) > 0


def compute_alpha(
    *,
    strategy_return: float,
    entry_date: date,
    exit_date: date,
    benchmark: KospiBenchmarkFetcher,
) -> Optional[float]:
    """전략 수익률 - 벤치마크 수익률.

    benchmark가 비어 있거나 가격 조회 실패 시 None 반환 (alpha 미산출).
    """
    if not benchmark.is_loaded():
        return None
    entry_p = benchmark.close_price("KOSPI", entry_date)
    exit_p = benchmark.close_price("KOSPI", exit_date)
    if entry_p <= 0 or exit_p <= 0:
        return None
    bench_return = (exit_p - entry_p) / entry_p
    return strategy_return - bench_return
