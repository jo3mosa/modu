"""candle_collector

DB(daily_ohlcv) 과거 60일 + KIS 실시간 스냅샷 → 6종 지표 계산 → Redis `technical:{stock_code}` 갱신.

활성 종목 전체(stock_master.is_active=1)를 ThreadPoolExecutor 로 병렬 순회.
공유 RateLimiter 가 KIS 전체 호출 빈도를 ≤ KIS_CALLS_PER_SEC 으로 유지.
종목당 KIS latency 가 sleep 안에 흡수되어 1 사이클 약 170~250초 (2,768 ÷ 16 = 173초 기준).

사용법:
    python -m collectors.candle_collector              # 1 사이클 실행 후 종료 (테스트)
    python -m collectors.candle_collector --loop       # 무한 루프 (Docker PID 1)
    python -m collectors.candle_collector --workers 4  # 워커 수 조정
"""

import argparse
import logging
import os
import sqlite3
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from typing import Optional

import numpy as np
import pandas as pd
from ta.momentum import RSIIndicator
from ta.trend import MACD, SMAIndicator
from ta.volatility import AverageTrueRange, BollingerBands
from ta.volume import MFIIndicator

from clients.kis_api_client import KisApiClient
from clients.redis_client import set_json

logger = logging.getLogger(__name__)

# ─── 설정 ────────────────────────────────────────────────────────────────────

_MODULE_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_DB = os.path.join(_MODULE_DIR, "..", "data", "stock_master.db")

# KIS Open API 호출 제한 ≈ 20 calls/sec. 16 으로 두면 안전 margin 확보하면서
# 2,768 ÷ 16 = 173초/cycle 이론 하한 달성.
KIS_CALLS_PER_SEC = 16

# 동시 in-flight 호출 수. KIS latency 100ms 가정 시 16 × 0.1 = 1.6 → 충분하려면 2 이상.
# 8 두면 latency 500ms 까지 흡수 가능 (16/sec × 0.5s = 8 in flight).
KIS_WORKER_THREADS = 8

# 지표 계산용 lookback (일봉). RSI/MFI(14) + MACD(26+9) 가 안정 수렴할 만큼.
LOOKBACK_DAYS = 60

# Redis TTL — 1 cycle 최대치보다 충분히 길어야 함 (만료 전에 재갱신 보장).
REDIS_TTL_SECONDS = 900

# 거래량 급증 임계: 최근 20일 평균 거래량의 N배 이상.
VOLUME_SPIKE_MULTIPLIER = 2.0

# 최소 데이터 요건: 60일 lookback 의 절반은 있어야 지표 의미 있음.
MIN_HISTORY_ROWS = 30


# ─── RateLimiter ────────────────────────────────────────────────────────────

class RateLimiter:
    """모든 스레드가 공유하는 시작 간격 강제기.

    동작: wait() 호출 시 직전 wait() 부터 최소 (1/rate) 초가 지나도록 sleep.
    여러 스레드에서 동시에 호출돼도 lock 으로 직렬화되어 strict rate 유지.
    """

    def __init__(self, calls_per_sec: float):
        self.interval = 1.0 / calls_per_sec
        self._lock = threading.Lock()
        self._next_at = time.monotonic()

    def wait(self) -> None:
        with self._lock:
            now = time.monotonic()
            wait_for = self._next_at - now
            self._next_at = max(now, self._next_at) + self.interval
        if wait_for > 0:
            time.sleep(wait_for)


# ─── DB 로딩 ─────────────────────────────────────────────────────────────────

def load_active_stocks(db_path: str = DEFAULT_DB) -> list[str]:
    """stock_master.is_active=1 인 종목 코드 리스트 (정렬)."""
    with sqlite3.connect(db_path) as conn:
        rows = conn.execute(
            "SELECT stock_code FROM stock_master WHERE is_active=1 ORDER BY stock_code"
        ).fetchall()
    return [r[0] for r in rows]


def load_recent_candles(stock_code: str, db_path: str = DEFAULT_DB) -> pd.DataFrame:
    """daily_ohlcv 에서 최근 LOOKBACK_DAYS 일치 캔들. 오래된 → 최신 순 정렬."""
    with sqlite3.connect(db_path) as conn:
        df = pd.read_sql(
            """
            SELECT date, open, high, low, close, volume
            FROM daily_ohlcv
            WHERE stock_code = ?
            ORDER BY date DESC
            LIMIT ?
            """,
            conn,
            params=(stock_code, LOOKBACK_DAYS),
        )
    return df.sort_values("date").reset_index(drop=True)


# ─── KIS 스냅샷 병합 ────────────────────────────────────────────────────────

def merge_realtime_snapshot(df: pd.DataFrame, snapshot: dict) -> pd.DataFrame:
    """KIS 실시간 스냅샷을 df 마지막 행으로 덮어쓰기 (같은 date 면 교체, 아니면 추가)."""
    today = snapshot["date"]
    new_row = {
        "date": today,
        "open": snapshot["open"],
        "high": snapshot["high"],
        "low": snapshot["low"],
        "close": snapshot["close"],
        "volume": snapshot["volume"],
    }
    if (df["date"] == today).any():
        df = df[df["date"] != today]
    return pd.concat([df, pd.DataFrame([new_row])], ignore_index=True)


# ─── 지표 계산 ──────────────────────────────────────────────────────────────

def calculate_indicators(df: pd.DataFrame) -> dict:
    """df → analysis_signals.signals.technical 형태 dict.

    df: date/open/high/low/close/volume 컬럼, 최근 N일. 길이 < MIN_HISTORY_ROWS 면 {}.
    """
    if len(df) < MIN_HISTORY_ROWS:
        return {}

    close = df["close"]
    high = df["high"]
    low = df["low"]
    volume = df["volume"]

    # RSI
    rsi = RSIIndicator(close, window=14).rsi()
    rsi_now = _safe_last(rsi)
    rsi_prev = _safe_nth_last(rsi, 2)

    # MACD
    macd_ind = MACD(close, window_slow=26, window_fast=12, window_sign=9)
    macd = macd_ind.macd()
    macd_signal = macd_ind.macd_signal()
    macd_now = _safe_last(macd)
    sig_now = _safe_last(macd_signal)
    macd_state = _classify_macd_state(
        macd_now, sig_now, _safe_nth_last(macd, 2), _safe_nth_last(macd_signal, 2),
    )

    # Bollinger Bands
    bb = BollingerBands(close, window=20, window_dev=2)
    bb_upper = _safe_last(bb.bollinger_hband())
    bb_lower = _safe_last(bb.bollinger_lband())
    close_now = _safe_last(close)
    bb_position = _classify_bb_position(close_now, bb_upper, bb_lower)

    # SMA
    sma_5 = _safe_last(SMAIndicator(close, window=5).sma_indicator())
    sma_20 = _safe_last(SMAIndicator(close, window=20).sma_indicator())
    sma_60 = _safe_last(SMAIndicator(close, window=60).sma_indicator())
    sma_alignment = _classify_sma_alignment(sma_5, sma_20, sma_60)

    # ATR
    atr_val = _safe_last(
        AverageTrueRange(high, low, close, window=14).average_true_range()
    )
    atr_ratio = (atr_val / close_now) if (atr_val and close_now) else None

    # MFI
    mfi = MFIIndicator(high, low, close, volume, window=14).money_flow_index()
    mfi_now = _safe_last(mfi)
    mfi_prev = _safe_nth_last(mfi, 2)

    # 스냅샷 (오늘 = 마지막 행)
    open_now = _safe_last(df["open"])
    high_now = _safe_last(high)
    low_now = _safe_last(low)
    volume_now = int(_safe_last(volume) or 0)
    close_prev = _safe_nth_last(close, 2)
    change_pct = ((close_now - close_prev) / close_prev * 100) if close_prev else None

    # 거래량 급증: 최근 20일 (오늘 제외) 평균 대비
    vol_history = volume.iloc[-21:-1] if len(volume) >= 21 else volume.iloc[:-1]
    vol_avg = float(vol_history.mean()) if len(vol_history) > 0 else None
    volume_spike = bool(vol_avg and volume_now > VOLUME_SPIKE_MULTIPLIER * vol_avg)

    return {
        "snapshot": {
            "open":         _to_int(open_now),
            "high":         _to_int(high_now),
            "low":          _to_int(low_now),
            "close":        _to_int(close_now),
            "volume":       volume_now,
            "change_pct":   _round(change_pct, 2),
            "volume_spike": volume_spike,
        },
        "trend": {
            "sma_5":         _round(sma_5),
            "sma_20":        _round(sma_20),
            "sma_60":        _round(sma_60),
            "sma_alignment": sma_alignment,
            "macd":          _round(macd_now, 4),
            "macd_signal":   _round(sig_now, 4),
            "macd_state":    macd_state,
        },
        "momentum": {
            "rsi_14":      _round(rsi_now, 2),
            "rsi_14_prev": _round(rsi_prev, 2),
        },
        "volatility": {
            "bb_upper":           _round(bb_upper),
            "bb_lower":           _round(bb_lower),
            "bollinger_position": bb_position,
            "atr":                _round(atr_val),
            "atr_ratio":          _round(atr_ratio, 4),
        },
        "volume": {
            "mfi_14":      _round(mfi_now, 2),
            "mfi_14_prev": _round(mfi_prev, 2),
        },
    }


# ─── 헬퍼 ───────────────────────────────────────────────────────────────────

def _safe_last(series) -> Optional[float]:
    if series is None or len(series) == 0:
        return None
    val = series.iloc[-1]
    return None if pd.isna(val) else float(val)


def _safe_nth_last(series, n: int) -> Optional[float]:
    """뒤에서 n번째 (n=2 → 직전값). 길이 부족 시 None."""
    if series is None or len(series) < n:
        return None
    val = series.iloc[-n]
    return None if pd.isna(val) else float(val)


def _classify_macd_state(macd, signal, macd_prev, signal_prev) -> Optional[str]:
    if None in (macd, signal, macd_prev, signal_prev):
        return None
    if macd_prev < signal_prev and macd > signal:
        return "bullish_cross"
    if macd_prev > signal_prev and macd < signal:
        return "bearish_cross"
    return "uptrend" if macd > signal else "downtrend"


def _classify_bb_position(close, upper, lower) -> Optional[str]:
    if None in (close, upper, lower):
        return None
    if close > upper:
        return "upper_breakout"
    if close < lower:
        return "lower_breakout"
    return "inside_band"


def _classify_sma_alignment(sma5, sma20, sma60) -> Optional[str]:
    if None in (sma5, sma20, sma60):
        return None
    if sma5 > sma20 > sma60:
        return "bullish_aligned"
    if sma5 < sma20 < sma60:
        return "bearish_aligned"
    return "mixed"


def _round(val: Optional[float], digits: int = 0) -> Optional[float]:
    return None if val is None else round(val, digits)


def _to_int(val) -> Optional[int]:
    if val is None:
        return None
    if isinstance(val, (np.integer, np.floating)):
        return int(val.item())
    return int(val)


# ─── 한 종목 처리 ───────────────────────────────────────────────────────────

def process_stock(stock_code: str, kis: KisApiClient, db_path: str = DEFAULT_DB) -> bool:
    """1 종목 → 지표 계산 → Redis 저장. 성공 True, 실패/skip False."""
    try:
        df = load_recent_candles(stock_code, db_path)
        if df.empty:
            return False

        snapshot = kis.get_realtime_snapshot(stock_code)
        if snapshot is not None:
            df = merge_realtime_snapshot(df, snapshot)
        # snapshot None 이면 어제까지의 DB 데이터로만 지표 계산 (degraded mode)

        indicators = calculate_indicators(df)
        if not indicators:
            return False

        set_json(f"technical:{stock_code}", indicators, ttl_seconds=REDIS_TTL_SECONDS)
        return True
    except Exception as e:
        logger.warning("process_stock(%s) failed: %s", stock_code, e)
        return False


# ─── 사이클 ─────────────────────────────────────────────────────────────────

def run_once(
    kis: KisApiClient,
    db_path: str = DEFAULT_DB,
    calls_per_sec: float = KIS_CALLS_PER_SEC,
    workers: int = KIS_WORKER_THREADS,
) -> dict:
    """활성 종목 전체 1 사이클 (스레드 풀 병렬). 통계 반환."""
    stocks = load_active_stocks(db_path)
    if not stocks:
        return {"ok": 0, "fail": 0, "total": 0, "elapsed_sec": 0.0}

    # 토큰 warm-up — 첫 호출에서 _issue_token 이 한 번만 일어나도록.
    # 8개 워커가 동시에 처음 호출하면 lock 안에서 한 번만 발급되긴 하지만,
    # 미리 한 번 호출해두면 모든 워커가 fast path 만 타게 됨.
    kis._get_valid_token()

    limiter = RateLimiter(calls_per_sec)
    started = time.monotonic()

    def worker(stock_code: str) -> bool:
        limiter.wait()
        return process_stock(stock_code, kis, db_path)

    ok = 0
    fail = 0
    with ThreadPoolExecutor(max_workers=workers, thread_name_prefix="candle") as ex:
        for success in ex.map(worker, stocks):
            if success:
                ok += 1
            else:
                fail += 1

    elapsed = time.monotonic() - started
    rate = len(stocks) / elapsed if elapsed > 0 else 0
    logger.info(
        "cycle done: %d ok / %d fail / %d total / %.1fs "
        "(%.2f stocks/s, workers=%d, rate=%.1f/s)",
        ok, fail, len(stocks), elapsed, rate, workers, calls_per_sec,
    )
    if elapsed > REDIS_TTL_SECONDS * 0.7:
        logger.warning(
            "cycle (%.1fs) approaches TTL (%ds) — 일부 종목 만료 위험. "
            "TTL/workers/rate 조정 필요",
            elapsed, REDIS_TTL_SECONDS,
        )
    return {"ok": ok, "fail": fail, "total": len(stocks), "elapsed_sec": elapsed}


def run_forever(
    db_path: str = DEFAULT_DB,
    calls_per_sec: float = KIS_CALLS_PER_SEC,
    workers: int = KIS_WORKER_THREADS,
) -> None:
    """무한 루프 entrypoint (Docker PID 1)."""
    kis = KisApiClient()
    cycle = 0
    while True:
        cycle += 1
        logger.info("=== cycle %d start ===", cycle)
        try:
            run_once(kis, db_path, calls_per_sec, workers)
        except Exception:
            logger.exception("cycle %d crashed", cycle)
            time.sleep(5)   # 사이클 전체 실패 시 짧은 cooldown 후 재시도


# ─── CLI ────────────────────────────────────────────────────────────────────

def main():
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    parser = argparse.ArgumentParser(
        description="candle_collector — KIS + DB → 지표 → Redis (병렬)"
    )
    parser.add_argument("--loop", action="store_true",
                        help="무한 루프 (Docker PID 1 운영 모드). 기본은 1 사이클 후 종료")
    parser.add_argument("--db", default=DEFAULT_DB, help="stock_master.db 경로")
    parser.add_argument("--workers", type=int, default=KIS_WORKER_THREADS,
                        help=f"동시 워커 스레드 수 (기본 {KIS_WORKER_THREADS})")
    parser.add_argument("--rate", type=float, default=KIS_CALLS_PER_SEC,
                        help=f"KIS 초당 호출 한도 (기본 {KIS_CALLS_PER_SEC})")
    args = parser.parse_args()

    if args.loop:
        run_forever(args.db, args.rate, args.workers)
    else:
        kis = KisApiClient()
        run_once(kis, args.db, args.rate, args.workers)


if __name__ == "__main__":
    main()
