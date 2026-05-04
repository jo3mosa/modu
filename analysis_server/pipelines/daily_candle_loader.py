import sqlite3
import pandas as pd
from pykrx import stock
import time
from datetime import datetime

def update_daily_candles(db_path="../data/stock_master.db"):
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()
    
    # 1. stock_master에서 활성화된 종목 코드만 추출
    try:
        target_stocks = pd.read_sql("SELECT stock_code FROM stock_master WHERE is_active = 1", conn)
        stock_codes = target_stocks['stock_code'].tolist()
    except Exception as e:
        print("[ERROR] 마스터 테이블을 읽을 수 없습니다. (에러:", e, ")")
        conn.close()
        return

    # 2. DB에서 가장 최근에 저장된 날짜 조회
    today_str = datetime.today().strftime("%Y%m%d")
    
    total = len(stock_codes)
    print(f"[START] 빈 구간 자동 채우기 업데이트 시작 (종목별 개별 날짜 ~ {today_str})")
    
    updated_count = 0
    for idx, code in enumerate(stock_codes):
        try:
            cursor.execute(
                "SELECT MAX(date) FROM daily_ohlcv WHERE stock_code = ?",
                (code,),
            )
            last_date_result = cursor.fetchone()[0]
            start_date_str = last_date_result.replace("-", "") if last_date_result else today_str

            # 마지막 저장일부터 오늘까지의 캔들 뭉텅이로 가져오기
            df = stock.get_market_ohlcv(start_date_str, today_str, code)
            
            if df.empty:
                continue
            
            df = df.reset_index()
            df = df.rename(columns={
                '날짜': 'date', '시가': 'open', '고가': 'high', 
                '저가': 'low', '종가': 'close', '거래량': 'volume'
            })
            df['date'] = df['date'].dt.strftime('%Y-%m-%d')
            
            records = df.to_dict('records')
            tuples = [(code, r['date'], r['open'], r['high'], r['low'], r['close'], r['volume']) for r in records]
            
            # REPLACE INTO로 겹치는 날짜(start_date)는 안전하게 최신 데이터로 덮어쓰기
            cursor.executemany("""
                REPLACE INTO daily_ohlcv (stock_code, date, open, high, low, close, volume)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """, tuples)
            
            updated_count += len(tuples)
            print(f"[{idx+1}/{total}] {code} 완료 ({len(tuples)}건 반영)")
            
            time.sleep(1)
            
        except Exception as e:
            print(f"[ERROR] {code} 처리 중 오류 발생: {e}")
            
    conn.commit()
    conn.close()
    print(f"[FIN] 캔들 업데이트 완료 (총 {updated_count}건 갱신)")

if __name__ == "__main__":
    update_daily_candles()