"""historical_indicator_loader.py

학습용 panel 데이터 적재 파이프라인.
daily_ohlcv → daily_indicators 로 (stock_code, date) 단위 기술 지표 + 캔들 패턴을 산출/적재한다.

실시간 분석용 indicator_calculator.py 와는 의도가 다르다:
 - indicator_calculator.py : KIS 실시간 스냅샷 + 최신 1행 → 분석 엔드포인트 응답
 - historical_indicator_loader.py : 과거 OHLCV 전체 → AI 학습용 panel
"""

import pandas as pd

from sqlalchemy import text
from ta.trend import SMAIndicator, MACD
from ta.momentum import RSIIndicator
from ta.volatility import BollingerBands, AverageTrueRange
from ta.volume import MFIIndicator

from clients.postgres_client import get_engine


# 테이블 DDL 은 Flyway (V20260515120000__add_analysis_data_tables.sql) 가 관리.


# ---------- 분류 헬퍼 ----------

def _classify_sma_alignment(s5, s20, s60):
    if pd.isna(s60) or pd.isna(s20) or pd.isna(s5):
        return "mixed"
    if s5 > s20 > s60:
        return "bullish_aligned"
    if s5 < s20 < s60:
        return "bearish_aligned"
    return "mixed"


def _classify_macd_state(macd_curr, sig_curr, macd_prev, sig_prev):
    if any(pd.isna(x) for x in (macd_curr, sig_curr, macd_prev, sig_prev)):
        return "mixed"
    if macd_prev <= sig_prev and macd_curr > sig_curr:
        return "bullish_cross"
    if macd_prev >= sig_prev and macd_curr < sig_curr:
        return "bearish_cross"
    return "uptrend" if macd_curr > sig_curr else "downtrend"


def _classify_bollinger(close, upper, lower):
    if pd.isna(upper) or pd.isna(lower) or pd.isna(close):
        return "inside_band"
    if close > upper:
        return "upper_breakout"
    if close < lower:
        return "lower_breakout"
    return "inside_band"


def _classify_candle(o, h, l, c):
    """단일 캔들 형태 분류. (pattern, body_ratio, upper_ratio, lower_ratio) 반환."""
    if any(pd.isna(x) for x in (o, h, l, c)):
        return "normal", None, None, None

    rng = h - l
    if rng <= 0:
        return "flat", 0.0, 0.0, 0.0

    body = abs(c - o)
    upper_shadow = h - max(o, c)
    lower_shadow = min(o, c) - l
    body_ratio = body / rng
    upper_ratio = upper_shadow / rng
    lower_ratio = lower_shadow / rng

    if body_ratio < 0.10:
        pattern = "doji"
    elif body_ratio > 0.70 and upper_ratio < 0.05 and lower_ratio < 0.05:
        pattern = "bullish_marubozu" if c > o else "bearish_marubozu"
    elif body > 0 and lower_shadow > 2 * body and upper_ratio < 0.10:
        pattern = "hammer"
    elif body > 0 and upper_shadow > 2 * body and lower_ratio < 0.10:
        pattern = "shooting_star"
    elif body_ratio > 0.60:
        pattern = "long_bullish" if c > o else "long_bearish"
    else:
        pattern = "normal"

    return pattern, round(body_ratio, 4), round(upper_ratio, 4), round(lower_ratio, 4)


# ---------- 핵심 계산 ----------

def compute_indicators_for_stock(ohlcv: pd.DataFrame) -> pd.DataFrame:
    """단일 종목의 OHLCV → indicator + candle + lag-return panel."""
    df = ohlcv.sort_values("date").reset_index(drop=True)
    for col in ("open", "high", "low", "close", "volume"):
        df[col] = pd.to_numeric(df[col], errors="coerce")

    # 추세
    df["sma_5"]  = SMAIndicator(close=df["close"], window=5).sma_indicator()
    df["sma_20"] = SMAIndicator(close=df["close"], window=20).sma_indicator()
    df["sma_60"] = SMAIndicator(close=df["close"], window=60).sma_indicator()

    macd = MACD(close=df["close"], window_slow=26, window_fast=12, window_sign=9)
    df["macd"] = macd.macd()
    df["macd_signal"] = macd.macd_signal()

    # 모멘텀
    df["rsi_14"] = RSIIndicator(close=df["close"], window=14).rsi()

    # 변동성
    bb = BollingerBands(close=df["close"], window=20, window_dev=2)
    df["bb_upper"] = bb.bollinger_hband()
    df["bb_lower"] = bb.bollinger_lband()

    atr = AverageTrueRange(high=df["high"], low=df["low"], close=df["close"], window=14)
    df["atr"] = atr.average_true_range()
    df["atr_ratio"] = (df["atr"] / df["close"]).round(5)

    # 거래량
    mfi = MFIIndicator(
        high=df["high"], low=df["low"], close=df["close"],
        volume=df["volume"], window=14,
    )
    df["mfi_14"] = mfi.money_flow_index()

    # state column들 (분류) — 행 단위 룰
    df["sma_alignment"] = [
        _classify_sma_alignment(s5, s20, s60)
        for s5, s20, s60 in zip(df["sma_5"], df["sma_20"], df["sma_60"])
    ]

    macd_prev = df["macd"].shift(1)
    sig_prev  = df["macd_signal"].shift(1)
    df["macd_state"] = [
        _classify_macd_state(mc, sc, mp, sp)
        for mc, sc, mp, sp in zip(df["macd"], df["macd_signal"], macd_prev, sig_prev)
    ]

    df["bollinger_position"] = [
        _classify_bollinger(c, u, l)
        for c, u, l in zip(df["close"], df["bb_upper"], df["bb_lower"])
    ]

    # 캔들 패턴
    candle_results = [
        _classify_candle(o, h, l, c)
        for o, h, l, c in zip(df["open"], df["high"], df["low"], df["close"])
    ]
    df["candle_pattern"]     = [r[0] for r in candle_results]
    df["candle_body_ratio"]  = [r[1] for r in candle_results]
    df["upper_shadow_ratio"] = [r[2] for r in candle_results]
    df["lower_shadow_ratio"] = [r[3] for r in candle_results]

    # 갭 (전일 종가 대비 시가)
    prev_close = df["close"].shift(1)
    df["gap_ratio"] = ((df["open"] - prev_close) / prev_close).round(5)

    # 과거 수익률 — feature, NOT forward
    df["return_1d"] = df["close"].pct_change(1).round(5)
    df["return_5d"] = df["close"].pct_change(5).round(5)

    return df


# ---------- 적재 파이프라인 ----------

OUTPUT_COLS = [
    "stock_code", "date",
    "sma_5", "sma_20", "sma_60", "macd", "macd_signal",
    "sma_alignment", "macd_state",
    "rsi_14",
    "bb_upper", "bb_lower", "bollinger_position",
    "atr", "atr_ratio",
    "mfi_14",
    "candle_pattern", "candle_body_ratio", "upper_shadow_ratio", "lower_shadow_ratio",
    "gap_ratio",
    "return_1d", "return_5d",
]

# OUTPUT_COLS 외에 PK 충돌 시 UPDATE 대상 컬럼 (stock_code, date 제외).
_INDICATOR_UPDATE_COLS = [c for c in OUTPUT_COLS if c not in ("stock_code", "date")]
_INDICATOR_UPSERT_SQL = (
    f"INSERT INTO daily_indicators ({', '.join(OUTPUT_COLS)}) "
    f"VALUES ({', '.join(['%s'] * len(OUTPUT_COLS))}) "
    f"ON CONFLICT (stock_code, date) DO UPDATE SET "
    + ", ".join(f"{c} = EXCLUDED.{c}" for c in _INDICATOR_UPDATE_COLS)
)

# 백테스트 panel 은 과거 분석용이라 현재 활성 종목만 보면 상장폐지된 종목이
# 통째로 빠진다 (survivorship bias). 기본 universe = daily_ohlcv 에 적재된
# 모든 종목 (= 그 시점에 실제 거래되던 종목 합집합).
_HISTORICAL_STOCKS_SQL = text(
    "SELECT DISTINCT stock_code FROM daily_ohlcv ORDER BY stock_code"
)

# date 는 Postgres DATE — pandas 비교 시 string 으로 유지하면 기존 로직 변경 최소.
_OHLCV_SQL = text(
    "SELECT TO_CHAR(date, 'YYYY-MM-DD') AS date, open, high, low, close, volume "
    "FROM daily_ohlcv WHERE stock_code = :stock_code ORDER BY date ASC"
)


def load_indicators(
    start_date=None,
    end_date=None,
    stock_codes=None,
    min_history_rows=60,
):
    """daily_ohlcv 전체를 읽어 daily_indicators panel 적재.

    Args:
        start_date / end_date : 'YYYY-MM-DD' (포함). None 이면 전 기간.
                                지표 계산엔 OHLCV 전체를 사용하고, 적재 시점에만 이 구간으로 잘라낸다 (워밍업 자연 확보).
        stock_codes : 명시 리스트 또는 None (None이면 daily_ohlcv 에 적재된 모든
                      종목 — 상장폐지 종목도 포함. survivorship bias 회피).
        min_history_rows : 종목별 최소 OHLCV 행수. sma_60/macd 가 의미를 가지려면 60 이상 권장.
    """
    engine = get_engine()
    raw_conn = engine.raw_connection()
    try:
        cursor = raw_conn.cursor()

        if stock_codes is None:
            with engine.connect() as sa_conn:
                rows = sa_conn.execute(_HISTORICAL_STOCKS_SQL).fetchall()
            stock_codes = [r[0] for r in rows]

        total = len(stock_codes)
        print(f"[START] daily_indicators 적재 — 대상 {total}개 종목 "
              f"(구간: {start_date or '전체'} ~ {end_date or '전체'})")

        inserted_total = 0
        skipped = 0
        failed = 0

        for idx, code in enumerate(stock_codes, start=1):
            try:
                ohlcv = pd.read_sql(
                    _OHLCV_SQL,
                    engine,
                    params={"stock_code": code},
                )
                if len(ohlcv) < min_history_rows:
                    skipped += 1
                    print(f"[{idx}/{total}] {code} skip — rows={len(ohlcv)} (< {min_history_rows})")
                    continue

                indi = compute_indicators_for_stock(ohlcv)
                indi.insert(0, "stock_code", code)

                # 사용자가 지정한 구간만 적재 (이전 기간은 워밍업으로만 사용됨)
                if start_date:
                    indi = indi[indi["date"] >= start_date]
                if end_date:
                    indi = indi[indi["date"] <= end_date]
                if indi.empty:
                    continue

                # NaN → None 변환 (Postgres NULL 호환)
                out = indi[OUTPUT_COLS].astype(object).where(pd.notna(indi[OUTPUT_COLS]), None)
                tuples = list(out.itertuples(index=False, name=None))

                cursor.executemany(_INDICATOR_UPSERT_SQL, tuples)
                raw_conn.commit()
                inserted_total += len(tuples)
                print(f"[{idx}/{total}] {code} 완료 ({len(tuples)}건)")

            except Exception as e:
                raw_conn.rollback()
                failed += 1
                print(f"[ERROR] {code}: {e}")

        print(f"[FIN] 적재 완료 — 총 {inserted_total}건 / skip {skipped} / fail {failed}")
    finally:
        raw_conn.close()


if __name__ == "__main__":
    # 23~25년 train(23-24) + test(25) panel 적재
    load_indicators(
        start_date="2023-01-01",
        end_date="2025-12-31",
    )
