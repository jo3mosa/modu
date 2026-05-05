import FinanceDataReader as fdr
import pandas as pd
import sqlite3
import os
from datetime import datetime
from apscheduler.schedulers.background import BackgroundScheduler
import time


def update_krx_master_db(db_path="../data/stock_master.db"): # analysis/data/ DB 저장 추후 백엔드 DB 서버로 변경
    print("  [START] KRX 전 종목 마스터 데이터(최신) 수집")
    
    # ====================================
    # 1. 최신 데이터 수집 및 필터링
    # ====================================
    df_master = fdr.StockListing('KRX-DESC')
    df_new = df_master.dropna(subset=['Sector']).copy()

    # ====================================
    # 2. ERD 규격에 맞춰 컬럼명 변경
    # ====================================
    df_new = df_new.rename(columns={
        'Code': 'stock_code',
        'Name': 'stock_name',
        'Market': 'market_type',
        'Sector': 'sector',
        'ListingDate': 'listed_at'
    })
    
    # 필요한 컬럼만 추출
    columns_to_keep = ['stock_code', 'stock_name', 'market_type', 'sector', 'listed_at']
    df_new = df_new[columns_to_keep]
    
    # ====================================
    # 3. ERD 필수 필드 추가
    # ====================================
    now = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
    df_new['is_active'] = True       # Boolean 타입 (SQLite에서는 1로 저장됨)
    df_new['created_at'] = now
    df_new['updated_at'] = now
    
    print(f"[COMPARE] 기존 DB 데이터 로드 및 비교 준비 (경로: {os.path.abspath(db_path)})")
    
    # DB 폴더가 없다면 생성
    os.makedirs(os.path.dirname(db_path), exist_ok=True)
    conn = sqlite3.connect(db_path)
    
    try:
        # (1) 기존 로컬 DB 읽어오기
        df_old = pd.read_sql("SELECT * FROM stock_master", conn)
        
        # (2) 상태 비교
        old_codes = set(df_old['stock_code'])
        new_codes = set(df_new['stock_code'])
        
        # (3) 신규 상장 종목 감지 (새로운 리스트에는 있지만 과거 DB에는 없는 것)
        new_listings = new_codes - old_codes
        if new_listings:
            print(f"--------- 신규 상장: {len(new_listings)} 종목 추가")
            
        # (4) 상장 폐지 종목 감지 (과거 DB에는 있지만 새로운 리스트에는 없는 것)
        delisted_codes = old_codes - new_codes
        if delisted_codes:
            print(f"--------- 상장 폐지: {len(delisted_codes)} 종목 비활성화")
            
            # 기존 DB에서 상장 폐지된 종목만 추출하여 is_active를 0(비활성)으로 변경
            df_delisted = df_old[df_old['stock_code'].isin(delisted_codes)].copy()
            df_delisted['is_active'] = 0
            
            # 최신 활성 종목(df_new)과 비활성 종목(df_delisted) 병합
            df_final = pd.concat([df_new, df_delisted], ignore_index=True)
        else:
            df_final = df_new
            print("--------- 상장 폐지된 종목 없음")
            
    except pd.errors.DatabaseError: 
        # DB(또는 테이블)가 아예 없는 최초 실행인 경우
        print("--------- 최초 실행: 기존 DB가 없어 전체 신규 적재 진행")
        df_final = df_new

    print("[COLLECT] SQLite DB 업데이트 및 병합 데이터 적재")

    # 최신화된 전체 데이터를 DB에 덮어쓰기
    df_final.to_sql('stock_master', conn, if_exists='replace', index=False)
    conn.close()
    
    print(f"    [FIN] 총 {len(df_final)}개 (활성: {len(df_new)}, 비활성: {len(df_final)-len(df_new)}) 데이터 적재 성공")

if __name__ == "__main__":
    scheduler = BackgroundScheduler()

    # 매일 오전 08:00에 실행되도록 설정
    scheduler.add_job(func=update_krx_master_db, trigger="cron", hour=8, minute=0)
    scheduler.start()

    update_krx_master_db()

    # # 스케줄러는 백그라운드에서 돌기 때문에 메인 프로그램이 종료되면 같이 꺼집니다.
    # # 테스트를 위해 무한 루프를 걸어둡니다.
    # try:
    #     while True:
    #         time.sleep(1)
    # except (KeyboardInterrupt, SystemExit):
    #     scheduler.shutdown()