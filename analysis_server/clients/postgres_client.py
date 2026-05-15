"""postgres_client

analysis_server 전용 PostgreSQL 연결 헬퍼.

ai_agent/master_data_loader 와 동일하게 SQLAlchemy create_engine 사용 — pandas
read_sql/to_sql 와 raw text 쿼리 모두 한 엔진으로 처리 가능.

ENV: DB_HOST / DB_PORT / DB_NAME / DB_USERNAME / DB_PASSWORD
  - 호스트 실행: DB_HOST=localhost (compose 가 5432 매핑)
  - 컨테이너 실행: DB_HOST=postgres (compose 서비스명)

테이블: stock_master (read-only, backend 관리), daily_ohlcv, daily_fundamentals,
         daily_indicators, financial_statements (모두 분석 서버가 R/W)
"""

import os
from functools import lru_cache

from dotenv import load_dotenv
from sqlalchemy import create_engine
from sqlalchemy.engine import Engine, URL

# clients/ → analysis_server/ → modu/ 루트로 2단계 상위.
# master_data_loader 는 scripts/backfill/ 라 3단계인 점 주의.
_ENV_PATH = os.path.join(os.path.dirname(__file__), "../../.env")
load_dotenv(dotenv_path=_ENV_PATH)


@lru_cache(maxsize=1)
def get_engine() -> Engine:
    """프로세스 단위 싱글톤 SQLAlchemy Engine.

    redis_client.get_redis_client 와 같은 lru_cache 패턴 — import 시 연결
    강제 안 함, 테스트에서 monkeypatch 교체 가능.

    pool_pre_ping=True : 컨테이너 재시작 후 stale connection 자동 폐기.
    """
    host = os.getenv("DB_HOST", "localhost")
    port = int(os.getenv("DB_PORT", 5432))
    name = os.getenv("DB_NAME", "modu_db")
    user = os.getenv("DB_USERNAME", "postgres")
    password = os.getenv("DB_PASSWORD", "")

    # URL.create() — 비밀번호에 @ : / 등 특수문자 포함 시 f-string 파싱 오류 방지.
    url = URL.create(
        drivername="postgresql+psycopg2",
        username=user,
        password=password,
        host=host,
        port=port,
        database=name,
    )
    return create_engine(url, pool_pre_ping=True, future=True)


def check_postgres_connection() -> bool:
    """startup / health check 용. 실패 시 False."""
    from sqlalchemy import text
    try:
        with get_engine().connect() as conn:
            conn.execute(text("SELECT 1"))
        return True
    except Exception:
        return False
