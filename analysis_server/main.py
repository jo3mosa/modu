"""main

1분 주기 engine cycle 의 entry point.

APScheduler BlockingScheduler 가 매 분 :00 (KST) 에 watchlist 전 종목을 순회하며
signal_builder → detection_engine → cooldown_manager → event_publisher 사이클 실행.

collectors 는 별도 프로세스 (docker-compose) 로 띄움 — 이 모듈은 read-only 로
그들이 갱신한 Redis 데이터 + DB(daily_fundamentals) 만 본다.

사용법:
    python -m main                      # 무한 루프 (Docker PID 1)
    python -m main --once               # 활성 종목 1 사이클 후 종료 (디버깅)
    python -m main --once --watchlist 005930,000660   # 특정 종목만
"""

import argparse
import logging
import sys
import time
from functools import partial
from typing import Optional

from apscheduler.schedulers.blocking import BlockingScheduler
from apscheduler.triggers.cron import CronTrigger

from clients.kafka_client import check_kafka_connection
from clients.redis_client import check_redis_connection
from collectors.candle_collector import load_active_stocks
from engine import cooldown_manager, detection_engine, event_publisher, signal_builder

logger = logging.getLogger(__name__)

# KST 기준 cron — UTC 컨테이너에서도 한국장 시간 정합.
SCHEDULER_TIMEZONE = "Asia/Seoul"

# cycle 이 1분의 90% 초과하면 경고 — watchlist 축소 / parallel 화 검토 트리거.
CYCLE_BUDGET_WARN_SEC = 55.0


def process_stock_cycle(stock_code: str) -> dict:
    """1 종목 × 1 cycle. 통계 dict 반환.

    한 종목의 실패가 사이클 전체를 깨지 않게 try/except. 통계는
    {"detected": 룰 발화 수, "published": Kafka 발행 수} 형태.
    """
    try:
        signal = signal_builder.build(stock_code)
        rule_ids = detection_engine.detect(signal)
        if not rule_ids:
            return {"detected": 0, "published": 0}

        valid = cooldown_manager.filter_active(stock_code, rule_ids)
        if not valid:
            return {"detected": len(rule_ids), "published": 0}

        ok = event_publisher.publish(stock_code, valid, signal)
        return {"detected": len(rule_ids), "published": len(valid) if ok else 0}
    except Exception:
        logger.exception("cycle failed for %s", stock_code)
        return {"detected": 0, "published": 0}


def run_cycle(watchlist: Optional[list[str]] = None) -> dict:
    """1 분 cycle — watchlist 전 종목 순회. 통계 dict 반환."""
    started = time.monotonic()
    stocks = watchlist if watchlist is not None else load_active_stocks()

    detected = 0
    published = 0
    for stock_code in stocks:
        stats = process_stock_cycle(stock_code)
        detected += stats["detected"]
        published += stats["published"]

    elapsed = time.monotonic() - started
    logger.info(
        "cycle done: %d stocks / %d rules detected / %d events published / %.1fs",
        len(stocks), detected, published, elapsed,
    )
    if elapsed > CYCLE_BUDGET_WARN_SEC:
        logger.warning(
            "cycle (%.1fs) 가 1분 budget 의 90%% 초과 — watchlist 축소 또는 "
            "ThreadPoolExecutor 도입 검토 (현재는 sequential)",
            elapsed,
        )
    return {"stocks": len(stocks), "detected": detected, "published": published,
            "elapsed_sec": elapsed}


def run_forever(watchlist: Optional[list[str]] = None) -> None:
    """BlockingScheduler — 매 분 :00 (KST) 에 run_cycle 실행.

    `max_instances=1` 로 cycle 이 1분 초과 시 다음 tick skip (overlap 방지).
    `misfire_grace_time=30` 으로 30 초 늦은 실행까지 허용.
    """
    scheduler = BlockingScheduler(timezone=SCHEDULER_TIMEZONE)
    scheduler.add_job(
        partial(run_cycle, watchlist),
        trigger=CronTrigger(second=0, timezone=SCHEDULER_TIMEZONE),
        id="engine_cycle",
        name="1-minute engine cycle",
        max_instances=1,
        misfire_grace_time=30,
    )
    logger.info("engine scheduler started — cron every minute (%s)", SCHEDULER_TIMEZONE)
    try:
        scheduler.start()
    except (KeyboardInterrupt, SystemExit):
        logger.info("shutdown signal received — graceful exit")


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )
    parser = argparse.ArgumentParser(description="analysis_server engine entrypoint")
    parser.add_argument("--once", action="store_true",
                        help="1 사이클 후 종료 (디버깅용). 기본은 cron 무한 루프")
    parser.add_argument("--watchlist",
                        help="쉼표 구분 종목 코드 리스트 (테스트용). 기본은 활성 전체")
    args = parser.parse_args()

    watchlist = (
        [s.strip() for s in args.watchlist.split(",") if s.strip()]
        if args.watchlist else None
    )

    # 의존성 헬스체크 — 실패 시 exit code 1 (docker restart: on-failure / k8s
    # restartPolicy: OnFailure 가 재기동 트리거, 좀비 컨테이너 방지).
    if not check_redis_connection():
        logger.error("Redis 연결 실패 — exit (REDIS_HOST 환경변수 확인)")
        sys.exit(1)
    if not check_kafka_connection():
        logger.error("Kafka 연결 실패 — exit (KAFKA_BOOTSTRAP_SERVERS 환경변수 확인)")
        sys.exit(1)
    logger.info("dependencies OK (Redis + Kafka)")

    if args.once:
        run_cycle(watchlist)
    else:
        run_forever(watchlist)


if __name__ == "__main__":
    main()
