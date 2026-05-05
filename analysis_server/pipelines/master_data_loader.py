import FinanceDataReader as fdr
import pandas as pd
import os
from datetime import datetime
from apscheduler.schedulers.background import BackgroundScheduler
from sqlalchemy import create_engine, text

# ====================================
# DB 연결 설정
# 로컬 개발: .env 또는 환경변수에서 읽어옴
# 운영 배포: Jenkins 환경변수로 주입
# ====================================
DB_HOST = os.getenv("DB_HOST", "localhost")
DB_PORT = os.getenv("DB_PORT", "5432")
DB_NAME = os.getenv("DB_NAME", "modu_db")
DB_USERNAME = os.getenv("DB_USERNAME", "postgres")
DB_PASSWORD = os.getenv("DB_PASSWORD", "")

DATABASE_URL = f"postgresql+psycopg2://{DB_USERNAME}:{DB_PASSWORD}@{DB_HOST}:{DB_PORT}/{DB_NAME}"


def update_krx_master_db():
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
    df_new['is_active'] = True
    df_new['created_at'] = now
    df_new['updated_at'] = now

    engine = create_engine(DATABASE_URL)
    print(f"[CONNECT] PostgreSQL 연결: {DB_HOST}:{DB_PORT}/{DB_NAME}")

    with engine.connect() as conn:
        try:
            # (1) 기존 DB 읽어오기
            df_old = pd.read_sql("SELECT * FROM stock_master", conn)

            # (2) 상태 비교
            old_codes = set(df_old['stock_code'])
            new_codes = set(df_new['stock_code'])

            # (3) 신규 상장 종목 감지
            new_listings = new_codes - old_codes
            if new_listings:
                print(f"--------- 신규 상장: {len(new_listings)} 종목 추가")

            # (4) 상장 폐지 종목 감지
            delisted_codes = old_codes - new_codes
            if delisted_codes:
                print(f"--------- 상장 폐지: {len(delisted_codes)} 종목 비활성화")

                df_delisted = df_old[df_old['stock_code'].isin(delisted_codes)].copy()
                df_delisted['is_active'] = False

                df_final = pd.concat([df_new, df_delisted], ignore_index=True)
            else:
                df_final = df_new
                print("--------- 상장 폐지된 종목 없음")

        except Exception:
            # 테이블이 없는 최초 실행
            print("--------- 최초 실행: 기존 데이터가 없어 전체 신규 적재 진행")
            df_final = df_new

    print("[COLLECT] PostgreSQL DB 업데이트 및 병합 데이터 적재")

    with engine.connect() as conn:
        # stock_code 기준 upsert: 기존 데이터 삭제 후 전체 재적재
        conn.execute(text("TRUNCATE TABLE stock_master RESTART IDENTITY"))
        conn.commit()

    df_final.to_sql('stock_master', engine, if_exists='append', index=False)

    print(f"    [FIN] 총 {len(df_final)}개 (활성: {len(df_new)}, 비활성: {len(df_final) - len(df_new)}) 데이터 적재 성공")


if __name__ == "__main__":
    scheduler = BackgroundScheduler()

    # 매일 오전 08:00에 실행되도록 설정
    scheduler.add_job(func=update_krx_master_db, trigger="cron", hour=8, minute=0)
    scheduler.start()

    update_krx_master_db()
