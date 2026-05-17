import os
from functools import lru_cache
from pathlib import Path

from dotenv import load_dotenv
from langchain_openai import ChatOpenAI

_env_path = Path(__file__).resolve().parents[2] / ".env"
load_dotenv(_env_path)


@lru_cache(maxsize=None)
def _build_llm(temperature: float) -> ChatOpenAI:
    """
    temperature별로 LLM 인스턴스를 생성하고 캐시한다.

    같은 temperature 값이면 동일 인스턴스를 재사용한다.
    노드별로 다른 temperature를 사용할 수 있도록 maxsize=None으로 설정한다.
    """
    gms_key = os.getenv("GMS_KEY")
    if not gms_key:
        raise ValueError("GMS_KEY가 .env에서 로드되지 않았습니다.")
    return ChatOpenAI(
        model=os.getenv("OPENAI_MODEL", "gpt-4o-mini"),
        temperature=temperature,
        base_url="https://gms.ssafy.io/gmsapi/api.openai.com/v1",
        api_key=gms_key,
        request_timeout=30.0,
    )


def get_debate_llm() -> ChatOpenAI:
    """
    Bull/Bear Researcher용 LLM.

    자유 텍스트 토론에 사용하며, 다양한 논거 탐색을 위해
    structured 출력 노드보다 temperature를 높게 유지한다.

    DEBATE_TEMPERATURE 환경변수로 실험 시 조정한다. 기본값 0.2.
    """
    temperature = float(os.getenv("DEBATE_TEMPERATURE", "0.2"))
    return _build_llm(temperature)


def get_structured_llm() -> ChatOpenAI:
    """
    Strategy Manager / Decision Manager용 LLM.

    Pydantic 파싱이 필요한 JSON 구조 출력에 사용하며,
    일관성 확보를 위해 debate 노드보다 낮은 temperature를 권장한다.

    DECISION_TEMPERATURE 환경변수로 실험 시 조정한다. 기본값 0.1.
    """
    temperature = float(os.getenv("DECISION_TEMPERATURE", "0.1"))
    return _build_llm(temperature)
