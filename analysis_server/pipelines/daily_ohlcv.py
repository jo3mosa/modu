import sqlite3
import os

def create_candle_table(db_path="../data/stock_master.db"):
    
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()

    # daily_ohlcv 테이블 생성 DDL
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS daily_ohlcv (
            stock_code TEXT NOT NULL,
            date TEXT NOT NULL,          -- 'YYYY-MM-DD' 형식
            open INTEGER NOT NULL,       -- 시가
            high INTEGER NOT NULL,       -- 고가
            low INTEGER NOT NULL,        -- 저가
            close INTEGER NOT NULL,      -- 종가
            volume INTEGER NOT NULL,     -- 거래량
            PRIMARY KEY (stock_code, date)
        )
    """)
    
    # 조회 속도 향상을 위한 인덱스 생성
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_stock_date ON daily_ohlcv(stock_code, date);")
    
    conn.commit()
    conn.close()
    print("[SUCCESS] daily_ohlcv 테이블 생성 완료")

if __name__ == "__main__":
    create_candle_table()