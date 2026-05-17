import pandas as pd
from pykrx import stock
import time
from datetime import datetime

from sqlalchemy import text

from clients.postgres_client import get_engine


_ACTIVE_STOCKS_SQL = text(
    "SELECT stock_code FROM stock_master WHERE is_active = TRUE"
)

_DAILY_CANDLE_UPSERT_SQL = """
INSERT INTO daily_ohlcv (stock_code, date, open, high, low, close, volume)
VALUES (%s, %s, %s, %s, %s, %s, %s)
ON CONFLICT (stock_code, date) DO UPDATE SET
    open   = EXCLUDED.open,
    high   = EXCLUDED.high,
    low    = EXCLUDED.low,
    close  = EXCLUDED.close,
    volume = EXCLUDED.volume
"""


def update_daily_candles():
    """종목별 마지막 적재일 ~ 오늘 사이 캔들을 pykrx 로 받아 upsert.

    겹치는 날짜는 ON CONFLICT 로 안전하게 갱신 (장중 호출되면 종가가 변동).
    """
    engine = get_engine()
    raw_conn = engine.raw_connection()
    try:
        cursor = raw_conn.cursor()

        try:
            target_stocks = pd.read_sql(_ACTIVE_STOCKS_SQL, engine)
            stock_codes = target_stocks["stock_code"].tolist()
        except Exception as e:
            print("[ERROR] 마스터 테이블을 읽을 수 없습니다. (에러:", e, ")")
            return

        today_str = datetime.today().strftime("%Y%m%d")
        total = len(stock_codes)
        print(f"[START] 빈 구간 자동 채우기 업데이트 시작 (종목별 개별 날짜 ~ {today_str})")

        updated_count = 0
        for idx, code in enumerate(stock_codes):
            try:
                cursor.execute(
                    "SELECT TO_CHAR(MAX(date), 'YYYY-MM-DD') FROM daily_ohlcv WHERE stock_code = %s",
                    (code,),
                )
                last_date_result = cursor.fetchone()[0]
                start_date_str = last_date_result.replace("-", "") if last_date_result else today_str

                df = stock.get_market_ohlcv(start_date_str, today_str, code)
                if df.empty:
                    continue

                df = df.reset_index()
                df = df.rename(columns={
                    "날짜": "date", "시가": "open", "고가": "high",
                    "저가": "low", "종가": "close", "거래량": "volume",
                })
                df["date"] = df["date"].dt.strftime("%Y-%m-%d")

                records = df.to_dict("records")
                tuples = [
                    (code, r["date"], r["open"], r["high"], r["low"], r["close"], r["volume"])
                    for r in records
                ]

                cursor.executemany(_DAILY_CANDLE_UPSERT_SQL, tuples)
                raw_conn.commit()

                updated_count += len(tuples)
                print(f"[{idx + 1}/{total}] {code} 완료 ({len(tuples)}건 반영)")

                time.sleep(1)

            except Exception as e:
                raw_conn.rollback()
                print(f"[ERROR] {code} 처리 중 오류 발생: {e}")

        print(f"[FIN] 캔들 업데이트 완료 (총 {updated_count}건 갱신)")
    finally:
        raw_conn.close()


if __name__ == "__main__":
    update_daily_candles()
