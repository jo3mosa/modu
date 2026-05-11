"""redis_client

analysis_server 전용 redis 연결 + JSON blob 헬퍼.

ai_agent/app/config/redis.py 와 같은 lru_cache 싱글톤 패턴을 따르되,
1분 사이클에서 다회 호출되므로 타임아웃을 1s 로 짧게 두어 hang 시 빠르게 fail.
키 컨벤션은 docs/architecture: technical:{stock}, event:{stock},
sentiment:{stock}, cooldown:{stock}:{rule_id}.
"""

import json
import os
from functools import lru_cache
from typing import Optional

import redis
from dotenv import load_dotenv

load_dotenv()


@lru_cache(maxsize=1)
def get_redis_client() -> redis.Redis:
    """프로세스 단위 싱글톤 redis client.

    함수 + lru_cache 구조 이유:
      1. import 시점에 연결을 강제하지 않음 (Redis down 이어도 import 가능)
      2. 테스트에서 monkeypatch 로 교체 가능
      3. 매 호출마다 새 client 생성 방지
    """
    host = os.getenv("REDIS_HOST", "localhost")
    port = int(os.getenv("REDIS_PORT", 6379))
    db = int(os.getenv("REDIS_DB", 0))
    password = os.getenv("REDIS_PASSWORD")

    return redis.Redis(
        host=host,
        port=port,
        db=db,
        password=password,
        decode_responses=True,
        socket_connect_timeout=1.0,
        socket_timeout=1.0,
    )


def check_redis_connection() -> bool:
    """startup / health check 용. 실패 시 False."""
    try:
        get_redis_client().ping()
        return True
    except redis.RedisError:
        return False


# JSON blob 값을 다루는 String 키 (technical/event/sentiment) 용 헬퍼.
# cooldown 키는 단순 String "1" + TTL 이라 별도 헬퍼 불요 — 호출부에서
# client.set(key, "1", ex=ttl) / client.exists(key) 로 충분.

def get_json(key: str) -> Optional[dict]:
    raw = get_redis_client().get(key)
    if raw is None:
        return None
    return json.loads(raw)


def set_json(key: str, value: dict, ttl_seconds: int) -> None:
    get_redis_client().set(key, json.dumps(value, ensure_ascii=False), ex=ttl_seconds)
