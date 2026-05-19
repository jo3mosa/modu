"""llm_client

SSAFY GMS 게이트웨이 경유 OpenAI 호출 래퍼.

GMS 엔드포인트:
    POST https://gms.ssafy.io/gmsapi/api.openai.com/v1/chat/completions
    Headers: Authorization: Bearer $GMS_KEY

사용처:
  - engine.event_publisher._summarize_news : 트리거 발화 시 뉴스 LLM 요약
"""

import logging
import os
from functools import lru_cache
from typing import Optional

from dotenv import load_dotenv

try:
    from openai import OpenAI
    from openai import APIError as OpenAIAPIError
except ImportError:
    OpenAI = None
    OpenAIAPIError = Exception

load_dotenv()

logger = logging.getLogger(__name__)


# GMS 가 openai.com 으로 프록시.
GMS_OPENAI_BASE_URL = "https://gms.ssafy.io/gmsapi/api.openai.com/v1"

# 요약 태스크 전용 모델. 운영 튜닝 시 환경변수로 override.
DEFAULT_SUMMARY_MODEL = "gpt-4o-mini"

# 요약 결과 길이 cap. 너무 길면 ai_agent 의 context budget 침범, 너무 짧으면
# 맥락 손실 — 500 tokens ≈ 350~400 한국어 단어 정도.
DEFAULT_MAX_TOKENS = 500

# API 호출 timeout — 트리거 발화 latency 예산이 ~15초라 그 안쪽으로.
REQUEST_TIMEOUT_SEC = 12.0


@lru_cache(maxsize=1)
def get_openai_client() -> Optional["OpenAI"]:
    """프로세스 단위 싱글톤 OpenAI client. 미설치/키 없음/실패는 모두 None 반환.

    None 반환 시 caller 는 요약 없이 진행 — 트리거 발행 자체는 막지 않음 (요약은
    부가 정보, 필수 아님).
    """
    if OpenAI is None:
        logger.warning("openai SDK 미설치 (pip install openai) — 요약 skip")
        return None

    api_key = os.getenv("GMS_KEY")
    if not api_key:
        logger.warning("GMS_KEY 환경변수 없음 — 요약 skip")
        return None

    try:
        client = OpenAI(
            api_key=api_key,
            base_url=GMS_OPENAI_BASE_URL,
            timeout=REQUEST_TIMEOUT_SEC,
        )
        logger.info("✓ OpenAI client 초기화 — GMS proxy / %s", DEFAULT_SUMMARY_MODEL)
        return client
    except Exception:
        logger.exception("OpenAI client 초기화 실패")
        return None


def summarize(
    *,
    system: str,
    user: str,
    model: str = DEFAULT_SUMMARY_MODEL,
    max_tokens: int = DEFAULT_MAX_TOKENS,
) -> Optional[str]:
    """단발성 chat completions 호출 → 텍스트 응답.

    실패 (client 없음 / API error / 빈 응답) 시 모두 None — caller 는 None 을
    "요약 없음" 으로 처리할 것. 예외를 caller 로 escape 시키지 않는다 (트리거
    발행이 LLM 장애로 깨지면 안 됨).
    """
    client = get_openai_client()
    if client is None:
        return None

    try:
        resp = client.chat.completions.create(
            model=model,
            max_tokens=max_tokens,
            messages=[
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
        )
    except OpenAIAPIError as e:
        logger.warning("OpenAI API error (model=%s): %s", model, e)
        return None
    except Exception:
        logger.exception("OpenAI 호출 중 예외")
        return None

    out = (resp.choices[0].message.content or "").strip()
    return out or None
