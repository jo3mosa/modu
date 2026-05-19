"""llm_client

SSAFY GMS 게이트웨이 경유 OpenAI 호환 LLM 호출 래퍼.

ai_agent/app/config/llm.py 와 동일한 게이트웨이 경로 사용 — 모델만 다름.
요약 태스크엔 gpt-4.1-mini 가 가장 비용 효율적 (Haiku 4.5 대비 큰 폭 저렴).

GMS 엔드포인트:
    POST https://gms.ssafy.io/gmsapi/api.openai.com/v1/chat/completions
    Headers: Authorization: Bearer $GMS_KEY (openai SDK 가 자동 부착)

사용처:
  - engine.event_publisher._summarize_news : 트리거 발화 시 뉴스 LLM 요약
  - scripts.backfill.validate_news_window  : 검증 시 요약 생성
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


# GMS 의 OpenAI 호환 경로 — ai_agent/app/config/llm.py 와 동일.
GMS_OPENAI_BASE_URL = "https://gms.ssafy.io/gmsapi/api.openai.com/v1"

# 요약 전용 모델 — gpt-4.1-mini 가 한국어 금융 텍스트 요약에 충분하고 가장 저렴.
# Haiku 4.5 대비 큰 폭 비용 절감. 환경변수로 override 가능.
DEFAULT_SUMMARY_MODEL = os.getenv("SUMMARY_MODEL", "gpt-4.1-mini")

# 요약 결과 길이 cap. 한국어 ~350~400 단어 정도.
DEFAULT_MAX_TOKENS = 500

# API 호출 timeout — 트리거 발화 latency 예산 ~15초 안쪽.
REQUEST_TIMEOUT_SEC = 12.0


@lru_cache(maxsize=1)
def get_openai_client() -> Optional["OpenAI"]:
    """프로세스 단위 싱글톤 OpenAI client. 미설치/키 없음/실패는 모두 None 반환.

    None 반환 시 caller 는 요약 없이 진행 — 트리거 발행 자체는 막지 않음.
    """
    if OpenAI is None:
        logger.warning("openai SDK 미설치 (pip install openai) — 요약 skip")
        return None

    api_key = os.getenv("GMS_KEY")
    if not api_key:
        logger.warning("GMS_KEY 환경변수 없음 — 요약 skip")
        return None

    try:
        # max_retries=0 — OpenAI SDK 기본값(2)은 408/409/429/5xx + 타임아웃을 자동 재시도해
        # 요약이 best-effort 인 트리거 발행 경로에서 latency 예산을 초과시킬 수 있음.
        # 실패하면 None 반환 후 caller 가 요약 없이 진행하므로 재시도 가치 낮음.
        client = OpenAI(
            api_key=api_key,
            base_url=GMS_OPENAI_BASE_URL,
            timeout=REQUEST_TIMEOUT_SEC,
            max_retries=0,
        )
        logger.info("OpenAI client 초기화 완료 — GMS proxy / %s", DEFAULT_SUMMARY_MODEL)
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
    """단발성 chat.completions 호출 → 텍스트 응답.

    실패 (client 없음 / API error / 빈 응답) 시 모두 None — caller 는 None 을
    "요약 없음" 으로 처리할 것. 예외를 caller 로 escape 시키지 않는다 (트리거
    발행이 LLM 장애로 깨지면 안 됨).
    """
    client = get_openai_client()
    if client is None:
        return None

    try:
        # max_tokens 는 Chat Completions 에서 deprecated, o1 시리즈와 미호환.
        # SUMMARY_MODEL 환경변수로 모델 교체 가능하므로 미래 호환성 위해 max_completion_tokens 사용.
        resp = client.chat.completions.create(
            model=model,
            max_completion_tokens=max_tokens,
            messages=[
                {"role": "system", "content": system},
                {"role": "user",   "content": user},
            ],
        )
    except OpenAIAPIError as e:
        logger.warning("OpenAI API error (model=%s): %s", model, e)
        return None
    except Exception:
        logger.exception("OpenAI 호출 중 예외")
        return None

    if not resp.choices:
        return None
    text = (resp.choices[0].message.content or "").strip()
    return text or None
