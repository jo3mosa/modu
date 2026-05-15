import pandas as pd
from pykrx import stock
import time
from datetime import datetime
from dateutil.relativedelta import relativedelta

from sqlalchemy import text

from clients.postgres_client import get_engine


_ACTIVE_STOCKS_SQL = text(
    "SELECT stock_code FROM stock_master WHERE is_active = TRUE"
)

# 기존 SQLite 의 INSERT OR IGNORE 와 동치: PK 충돌 시 무시 (이미 적재된 과거 행은 보존).
_DAILY_CANDLE_INSERT_IGNORE_SQL = """
INSERT INTO daily_ohlcv (stock_code, date, open, high, low, close, volume)
VALUES (%s, %s, %s, %s, %s, %s, %s)
ON CONFLICT (stock_code, date) DO NOTHING
"""


def fetch_and_insert_historical_candles(years=5):
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

        end_date_str = datetime.today().strftime("%Y%m%d")
        start_date_str = (datetime.today() - relativedelta(years=years)).strftime("%Y%m%d")

        total = len(stock_codes)
        print(f"[START] 총 {total}개 종목의 과거 일봉 수집 시작 ({start_date_str} ~ {end_date_str})")

        for idx, code in enumerate(stock_codes):
            try:
                df = stock.get_market_ohlcv(start_date_str, end_date_str, code)

                if df.empty:
                    print(f"[{idx + 1}/{total}] {code} 데이터 없음 (상장 폐지 또는 신규 상장)")
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

                cursor.executemany(_DAILY_CANDLE_INSERT_IGNORE_SQL, tuples)
                raw_conn.commit()
                print(f"[{idx + 1}/{total}] {code} 수집 및 적재 완료 ({len(tuples)}건)")

                time.sleep(1)

            except Exception as e:
                raw_conn.rollback()
                print(f"[ERROR] {code}: {e}")

        print("[FIN] 모든 과거 일봉 데이터 적재 완료")
    finally:
        raw_conn.close()


if __name__ == "__main__":
    fetch_and_insert_historical_candles(years=5)
