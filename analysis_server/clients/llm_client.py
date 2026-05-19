"""llm_client

SSAFY GMS 게이트웨이 경유 Anthropic Claude 호출 래퍼.

ai_agent/app/config/llm.py 는 langchain_openai + OpenAI 호환 경로를 쓰고,
analysis_server 는 별도로 Anthropic 네이티브 messages API 를 쓴다 — Haiku 4.5 가
한국어 금융 요약에 충분히 좋고 OpenAI 모델 대비 long-context 처리 비용이 낮음.

GMS 엔드포인트:
    POST https://gms.ssafy.io/gmsapi/api.anthropic.com/v1/messages
    Headers: x-api-key: $GMS_KEY, anthropic-version: 2023-06-01

anthropic SDK 가 base_url 만 바꾸면 그대로 동작 — 인증 헤더(x-api-key)는
api_key 파라미터로 자동 부착.

사용처:
  - engine.event_publisher._summarize_news : 트리거 발화 시 뉴스 LLM 요약
"""

import logging
import os
from functools import lru_cache
from typing import Optional

from dotenv import load_dotenv

try:
    from anthropic import Anthropic
    from anthropic import APIError as AnthropicAPIError
except ImportError:
    Anthropic = None
    AnthropicAPIError = Exception

load_dotenv()

logger = logging.getLogger(__name__)


# GMS 가 anthropic.com 으로 프록시. SDK 가 /v1/messages 자동 부착.
GMS_ANTHROPIC_BASE_URL = "https://gms.ssafy.io/gmsapi/api.anthropic.com"

# 요약 태스크 전용 모델 — context 200k, max output 64k. Haiku 4.5 가 한국어 금융
# 텍스트 요약에 충분 (Sonnet 으로 올리면 비용 ~3-5배). 운영 튜닝 시 환경변수로 override.
DEFAULT_SUMMARY_MODEL = "claude-haiku-4-5-20251001"

# 요약 결과 길이 cap. 너무 길면 ai_agent 의 context budget 침범, 너무 짧으면
# 맥락 손실 — 500 tokens ≈ 350~400 한국어 단어 정도.
DEFAULT_MAX_TOKENS = 500

# API 호출 timeout — 트리거 발화 latency 예산이 ~15초라 그 안쪽으로.
REQUEST_TIMEOUT_SEC = 12.0


@lru_cache(maxsize=1)
def get_anthropic_client() -> Optional["Anthropic"]:
    """프로세스 단위 싱글톤 Anthropic client. 미설치/키 없음/실패는 모두 None 반환.

    None 반환 시 caller 는 요약 없이 진행 — 트리거 발행 자체는 막지 않음 (요약은
    부가 정보, 필수 아님).
    """
    if Anthropic is None:
        logger.warning("anthropic SDK 미설치 (pip install anthropic) — 요약 skip")
        return None

    api_key = os.getenv("GMS_KEY")
    if not api_key:
        logger.warning("GMS_KEY 환경변수 없음 — 요약 skip")
        return None

    try:
        client = Anthropic(
            api_key=api_key,
            base_url=GMS_ANTHROPIC_BASE_URL,
            timeout=REQUEST_TIMEOUT_SEC,
        )
        logger.info("✓ Anthropic client 초기화 — GMS proxy / %s", DEFAULT_SUMMARY_MODEL)
        return client
    except Exception:
        logger.exception("Anthropic client 초기화 실패")
        return None


def summarize(
    *,
    system: str,
    user: str,
    model: str = DEFAULT_SUMMARY_MODEL,
    max_tokens: int = DEFAULT_MAX_TOKENS,
) -> Optional[str]:
    """단발성 messages API 호출 → 텍스트 응답.

    실패 (client 없음 / API error / 빈 응답) 시 모두 None — caller 는 None 을
    "요약 없음" 으로 처리할 것. 예외를 caller 로 escape 시키지 않는다 (트리거
    발행이 LLM 장애로 깨지면 안 됨).
    """
    client = get_anthropic_client()
    if client is None:
        return None

    try:
        resp = client.messages.create(
            model=model,
            max_tokens=max_tokens,
            system=system,
            messages=[{"role": "user", "content": user}],
        )
    except AnthropicAPIError as e:
        logger.warning("Anthropic API error (model=%s): %s", model, e)
        return None
    except Exception:
        logger.exception("Anthropic 호출 중 예외")
        return None

    # resp.content 는 list[ContentBlock]. text 블록만 추출 (tool_use 등 미사용).
    parts = []
    for block in resp.content or []:
        text = getattr(block, "text", None)
        if text:
            parts.append(text)
    out = "\n".join(parts).strip()
    return out or None
