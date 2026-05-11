import FinanceDataReader as fdr
import pandas as pd
import os
from datetime import datetime
from apscheduler.schedulers.background import BackgroundScheduler
from sqlalchemy import create_engine, text
from sqlalchemy.engine import URL
from sqlalchemy.exc import ProgrammingError
from dotenv import load_dotenv

# ====================================
# DB 연결 설정
# 로컬 개발: 모노레포 루트의 .env 파일 로드
# 운영 배포: Jenkins 환경변수로 주입 (.env 파일 없어도 동작)
# ====================================
# scripts/backfill/ → modu/ 루트로 3단계 상위
env_path = os.path.join(os.path.dirname(__file__), "../../../.env")
load_dotenv(dotenv_path=env_path)

DB_HOST = os.getenv("DB_HOST", "localhost")
DB_PORT = os.getenv("DB_PORT", "5432")
DB_NAME = os.getenv("DB_NAME", "modu_db")
DB_USERNAME = os.getenv("DB_USERNAME", "postgres")
DB_PASSWORD = os.getenv("DB_PASSWORD", "")

# URL.create() 사용: 비밀번호에 @, :, / 등 특수문자 포함 시 f-string URL 파싱 오류 방지
DATABASE_URL = URL.create(
    drivername="postgresql+psycopg2",
    username=DB_USERNAME,
    password=DB_PASSWORD,
    host=DB_HOST,
    port=int(DB_PORT),
    database=DB_NAME
)


def update_krx_master_db():
    print("  [START] KRX 전 종목 마스터 데이터(최신) 수집")

    # ====================================
    # 1. 최신 데이터 수집 및 필터링
    # ====================================
    df_master = fdr.StockListing('KRX-DESC')
    # KRX-DESC는 지주회사·ETF 등 일부 종목에서 Sector가 NaN으로 내려와
    # dropna 시 삼성전자(005930)·SK하이닉스(000660) 같은 시총 상위 종목이 누락됨.
    # 결측은 'Unknown'으로 채워 보존.
    df_new = df_master.copy()
    df_new['Sector'] = df_new['Sector'].fillna('Unknown')

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
    # 3. 시장 필터링 — KOSPI / KOSDAQ / KOSDAQ GLOBAL 만 유지
    # KONEX·ETF·ETN·ELW 등 분석 대상 외 종목 제거
    # ====================================
    allowed_markets = {'KOSPI', 'KOSDAQ', 'KOSDAQ GLOBAL'}
    before_filter = len(df_new)
    df_new = df_new[df_new['market_type'].isin(allowed_markets)].copy()
    print(f"--------- 시장 필터: {before_filter} → {len(df_new)} 종목 "
          f"(KOSPI/KOSDAQ/KOSDAQ GLOBAL 유지, 그 외 {before_filter - len(df_new)} 제외)")

    # ====================================
    # 4. ERD 필수 필드 추가
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

        except ProgrammingError as e:
            # 테이블이 없는 경우(42P01)만 첫 적재로 처리
            # 권한 오류, 스키마 불일치 등 다른 오류는 재예외 처리
            if "42P01" in str(e.orig) or "does not exist" in str(e.orig):
                print("--------- 최초 실행: 기존 데이터가 없어 전체 신규 적재 진행")
                df_final = df_new
            else:
                print(f"[ERROR] 예상치 못한 DB 오류: {e}")
                raise

    print("[COLLECT] PostgreSQL DB 업데이트 및 병합 데이터 적재")

    # 스테이징 테이블에 데이터 적재 후 upsert
    # TRUNCATE 대신 upsert 사용: orders 등 FK 참조 테이블이 있어 TRUNCATE 불가
    df_final.to_sql('stock_master_staging', engine, if_exists='replace', index=False)

    with engine.connect() as conn:
        conn.execute(text("""
            INSERT INTO stock_master
                (stock_code, stock_name, market_type, sector, is_active, listed_at, created_at, updated_at)
            SELECT
                stock_code, stock_name, market_type, sector, is_active,
                listed_at::date, created_at::timestamptz, updated_at::timestamptz
            FROM stock_master_staging
            ON CONFLICT (stock_code) DO UPDATE SET
                stock_name  = EXCLUDED.stock_name,
                market_type = EXCLUDED.market_type,
                sector      = EXCLUDED.sector,
                is_active   = EXCLUDED.is_active,
                listed_at   = EXCLUDED.listed_at,
                updated_at  = EXCLUDED.updated_at
        """))
        conn.execute(text("DROP TABLE IF EXISTS stock_master_staging"))
        conn.commit()

    print(f"    [FIN] 총 {len(df_final)}개 (활성: {len(df_new)}, 비활성: {len(df_final) - len(df_new)}) 데이터 적재 성공")


if __name__ == "__main__":
    scheduler = BackgroundScheduler()

    # 매일 오전 08:00에 실행되도록 설정
    scheduler.add_job(func=update_krx_master_db, trigger="cron", hour=8, minute=0)
    scheduler.start()

    update_krx_master_db()
