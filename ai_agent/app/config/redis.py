import os
from functools import lru_cache

import redis
from dotenv import load_dotenv

# .env 로드
load_dotenv()


@lru_cache(maxsize=1)
def get_redis_client() -> redis.Redis:
    """
    Redis client를 생성해서 반환한다.

    왜 함수 + cache 구조인가?
    ----------------------------
    1. import 시점에 Redis 연결을 강제하지 않기 위해
       -> Redis 서버가 꺼져 있어도 모듈 import 자체는 가능해야 함

    2. 테스트에서 mock 교체를 쉽게 하기 위해
       -> monkeypatch로 get_redis_client() 대체 가능

    3. Redis client를 매번 새로 만들지 않기 위해
       -> lru_cache로 singleton처럼 재사용
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
        socket_connect_timeout=3.0,
        socket_timeout=3.0,
    )


def check_redis_connection() -> bool:
    """
    Redis 연결 가능 여부 확인용 helper.

    startup 단계나 health check에서 사용할 수 있다.
    """

    try:
        client = get_redis_client()
        client.ping()
        return True

    except redis.RedisError:
        return False