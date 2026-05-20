import os
from functools import lru_cache
from pathlib import Path

from dotenv import load_dotenv
from langchain_core.language_models import BaseChatModel

_env_path = Path(__file__).resolve().parents[2] / ".env"
load_dotenv(_env_path)

_DEFAULT_MODEL = "gpt-4o-mini"


@lru_cache(maxsize=None)
def _build_llm(model: str, temperature: float) -> BaseChatModel:
    """model + temperature 조합으로 캐시. provider는 model 이름 접두사로 자동 분기.

    claude-* → ChatAnthropic  (ANTHROPIC_API_KEY)
    grok-*   → ChatXAI        (XAI_API_KEY)
    그 외    → ChatOpenAI     (GMS_KEY, SSAFY GMS 프록시)
    """
    if model.startswith("claude"):
        from langchain_anthropic import ChatAnthropic
        api_key = os.getenv("CLAUDE_API_KEY")
        if not api_key:
            raise ValueError("CLAUDE_API_KEY가 .env에 없습니다.")
        return ChatAnthropic(
            model=model,
            temperature=temperature,
            anthropic_api_key=api_key,
            timeout=float(os.getenv("LLM_REQUEST_TIMEOUT", "60.0")),
            max_retries=int(os.getenv("LLM_MAX_RETRIES", "5")),
        )

    if model.startswith("grok"):
        from langchain_xai import ChatXAI
        api_key = os.getenv("GROK_API_KEY")
        if not api_key:
            raise ValueError("GROK_API_KEY가 .env에 없습니다.")
        return ChatXAI(
            model=model,
            temperature=temperature,
            xai_api_key=api_key,
        )

    # OpenAI (SSAFY GMS 프록시)
    from langchain_openai import ChatOpenAI
    gms_key = os.getenv("GMS_KEY")
    if not gms_key:
        raise ValueError("GMS_KEY가 .env에서 로드되지 않았습니다.")
    # request_timeout/max_retries: backtest는 수 시간 단위로 돌아가며 중간에
    # wifi가 잠시 끊겨도 trigger 단위 fallback 강등 방지.
    return ChatOpenAI(
        model=model,
        temperature=temperature,
        base_url="https://gms.ssafy.io/gmsapi/api.openai.com/v1",
        api_key=gms_key,
        request_timeout=float(os.getenv("LLM_REQUEST_TIMEOUT", "60.0")),
        max_retries=int(os.getenv("LLM_MAX_RETRIES", "5")),
    )


def get_debate_llm() -> BaseChatModel:
    """Bull/Bear Researcher용 LLM.

    DEBATE_MODEL 환경변수로 모델 선택 (기본: gpt-4o-mini).
    DEBATE_TEMPERATURE로 온도 조정 (기본: 0.2).
    """
    model = os.getenv("DEBATE_MODEL", _DEFAULT_MODEL)
    temperature = float(os.getenv("DEBATE_TEMPERATURE", "0.2"))
    return _build_llm(model, temperature)


def get_strategy_llm() -> BaseChatModel:
    """Strategy Manager용 LLM.

    우선순위: STRATEGY_MODEL → STRUCTURED_MODEL → gpt-4o-mini.
    DECISION_TEMPERATURE로 온도 조정 (기본: 0.1).
    """
    model = (
        os.getenv("STRATEGY_MODEL")
        or os.getenv("STRUCTURED_MODEL")
        or _DEFAULT_MODEL
    )
    temperature = float(os.getenv("DECISION_TEMPERATURE", "0.1"))
    return _build_llm(model, temperature)


def get_decision_llm() -> BaseChatModel:
    """Decision Manager용 LLM.

    우선순위: DECISION_MODEL → STRUCTURED_MODEL → gpt-4o-mini.
    DECISION_TEMPERATURE로 온도 조정 (기본: 0.1).
    """
    model = (
        os.getenv("DECISION_MODEL")
        or os.getenv("STRUCTURED_MODEL")
        or _DEFAULT_MODEL
    )
    temperature = float(os.getenv("DECISION_TEMPERATURE", "0.1"))
    return _build_llm(model, temperature)


def get_structured_llm() -> BaseChatModel:
    """하위호환 alias → get_strategy_llm()."""
    return get_strategy_llm()
