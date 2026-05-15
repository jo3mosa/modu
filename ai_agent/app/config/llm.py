"""LLM provider/모델 swap.

설계 (옵션 1, 동질 profile):
- MODEL_PROFILE 환경변수로 (provider, model) 결정
- 모든 agent가 같은 LLM 사용 (homogeneous)
- production 코드 변화 없음 — agent들은 get_strategy_llm() 그대로 호출
- 실험 후 winner profile만 env에 박아두면 봉인

가용 profile 추가: _PROFILES dict에 한 줄 등록.
크로스 프로바이더 비교 실험은 MODEL_PROFILE만 swap하면 자동 동작.
"""
import os
from functools import lru_cache
from pathlib import Path
from typing import Literal

from dotenv import load_dotenv

_env_path = Path(__file__).resolve().parents[2] / ".env"
load_dotenv(_env_path)


Provider = Literal["gms", "anthropic", "xai"]


# 모델 ID는 각 provider 콘솔에서 가용한 것을 사용. 필요 시 dict 한 줄로 추가/수정.
_PROFILES: dict[str, tuple[Provider, str]] = {
    "gms_4o_mini":   ("gms", "gpt-4o-mini"),
    "gms_4o":        ("gms", "gpt-4o"),
    "claude_haiku":  ("anthropic", "claude-haiku-4-5-20251001"),
    "claude_sonnet": ("anthropic", "claude-sonnet-4-6"),
    "claude_opus":   ("anthropic", "claude-opus-4-7"),
    "grok_2":        ("xai", "grok-2-latest"),
    "grok_3":        ("xai", "grok-3"),
}

_DEFAULT_PROFILE = "gms_4o_mini"


def _current_profile_name() -> str:
    return os.getenv("MODEL_PROFILE", _DEFAULT_PROFILE)


def _resolve(profile_name: str) -> tuple[Provider, str]:
    if profile_name not in _PROFILES:
        raise ValueError(
            f"MODEL_PROFILE={profile_name}은 정의되지 않은 profile입니다. "
            f"가용 profile: {sorted(_PROFILES)}"
        )
    return _PROFILES[profile_name]


def _build_gms(model: str):
    from langchain_openai import ChatOpenAI
    api_key = os.getenv("GMS_KEY")
    if not api_key:
        raise ValueError("GMS_KEY가 .env에 없습니다.")
    return ChatOpenAI(
        model=model,
        temperature=0.2,
        base_url="https://gms.ssafy.io/gmsapi/api.openai.com/v1",
        api_key=api_key,
        request_timeout=30.0,
    )


def _build_anthropic(model: str):
    from langchain_anthropic import ChatAnthropic
    api_key = os.getenv("ANTHROPIC_API_KEY")
    if not api_key:
        raise ValueError("ANTHROPIC_API_KEY가 .env에 없습니다.")
    return ChatAnthropic(
        model=model,
        temperature=0.2,
        api_key=api_key,
        timeout=30.0,
    )


def _build_xai(model: str):
    from langchain_xai import ChatXAI
    api_key = os.getenv("XAI_API_KEY")
    if not api_key:
        raise ValueError("XAI_API_KEY가 .env에 없습니다.")
    return ChatXAI(
        model=model,
        temperature=0.2,
        api_key=api_key,
        timeout=30.0,
    )


_BUILDERS = {
    "gms": _build_gms,
    "anthropic": _build_anthropic,
    "xai": _build_xai,
}


@lru_cache(maxsize=16)
def _cached_llm(profile_name: str):
    provider, model = _resolve(profile_name)
    return _BUILDERS[provider](model)


def get_strategy_llm():
    """현재 MODEL_PROFILE에 해당하는 LLM 객체를 반환.

    실험 중 swap 예:
        os.environ["MODEL_PROFILE"] = "claude_sonnet"
        # 다음 호출부터 Claude 사용 (캐시는 (profile)로 분리되어 있어 cache_clear 불필요)
    """
    return _cached_llm(_current_profile_name())


def list_profiles() -> list[str]:
    """가용 profile 목록 반환. 실험 스크립트가 활용."""
    return sorted(_PROFILES)
