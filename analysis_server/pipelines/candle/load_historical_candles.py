import sqlite3
import pandas as pd
from pykrx import stock
import time
from datetime import datetime
from dateutil.relativedelta import relativedelta

def fetch_and_insert_historical_candles(db_path="../data/stock_master.db", years=5):
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()
    
    # 1. stock_master에서 활성화된 종목 코드만 추출
    try:
        target_stocks = pd.read_sql("SELECT stock_code FROM stock_master WHERE is_active = 1", conn)
        stock_codes = target_stocks['stock_code'].tolist()
    except Exception as e:
        print("❌ 마스터 테이블을 읽을 수 없습니다. (에러:", e, ")")
        conn.close()
        return

    # 2. 조회 기간 설정 (오늘부터 N년 전)
    end_date_str = datetime.today().strftime("%Y%m%d")
    start_date_str = (datetime.today() - relativedelta(years=years)).strftime("%Y%m%d")
    
    total = len(stock_codes)
    print(f"📥 [START] 총 {total}개 종목의 과거 일봉 수집 시작 ({start_date_str} ~ {end_date_str})")
    
    for idx, code in enumerate(stock_codes):
        try:
            # PyKrx로 OHLCV 데이터 가져오기
            df = stock.get_market_ohlcv(start_date_str, end_date_str, code)
            
            if df.empty:
                print(f"[{idx+1}/{total}] {code} 데이터 없음 (상장 폐지 또는 신규 상장)")
                continue
            
            # DB 컬럼 규격에 맞게 데이터프레임 정제
            df = df.reset_index()
            df = df.rename(columns={
                '날짜': 'date', '시가': 'open', '고가': 'high', 
                '저가': 'low', '종가': 'close', '거래량': 'volume'
            })
            
            # YYYY-MM-DD 포맷으로 문자열 변환
            df['date'] = df['date'].dt.strftime('%Y-%m-%d')
            
            # INSERT OR IGNORE를 위한 튜플 리스트 변환
            records = df.to_dict('records')
            tuples = [(code, r['date'], r['open'], r['high'], r['low'], r['close'], r['volume']) for r in records]
            
            # DB에 적재 (Primary Key가 겹치면 자동으로 무시됨)
            cursor.executemany("""
                INSERT OR IGNORE INTO daily_ohlcv (stock_code, date, open, high, low, close, volume)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """, tuples)
            
            conn.commit()
            print(f"[{idx+1}/{total}] {code} 수집 및 적재 완료 ({len(tuples)}건)")
            
            # KRX 서버 차단 방지를 위한 딜레이 (필수)
            time.sleep(1)
            
        except Exception as e:
            print(f"❌ Error at {code}: {e}")
            
    conn.close()
    print("✅ [FIN] 모든 과거 일봉 데이터 적재 완료")

if __name__ == "__main__":
    fetch_and_insert_historical_candles(years=5)