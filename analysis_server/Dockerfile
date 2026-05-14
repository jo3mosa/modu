FROM python:3.11-slim

# Container 로그 즉시 stdout 으로 flush — docker logs / k8s logs 에서 실시간 확인 가능.
# PYTHONPATH=/app — 모든 모듈을 `python -m <pkg.module>` 형태로 직접 실행 가능.
ENV PYTHONUNBUFFERED=1 \
    PYTHONPATH=/app

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

# 기본 CMD 는 backfill 1회 적재 (legacy analysis-pipeline 서비스 호환).
# 운영 서비스(engine / collectors)는 docker-compose 가 command 로 override:
#   python -m main                                     # engine 1분 cron
#   python -m collectors.candle_collector --loop       # 무한 1 사이클 ≈ 7~10분
#   python -m collectors.disclosure_collector --loop   # 3분 주기
#   python -m collectors.news_collector --loop         # 10분 주기
#
# 분기 백필 (DART quarterly):
#   python -m scripts.backfill.historical_fundamental_loader --start-date ... --end-date ...
#   python -m collectors.fundamental_loader              # daily_fundamentals 갱신
CMD ["python", "scripts/backfill/master_data_loader.py"]
