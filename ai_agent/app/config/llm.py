import os
from functools import lru_cache
from pathlib import Path

from dotenv import load_dotenv
from langchain_openai import ChatOpenAI

_env_path = Path(__file__).resolve().parents[2] / ".env"
load_dotenv(_env_path)


@lru_cache(maxsize=1)
def get_strategy_llm() -> ChatOpenAI:
    gms_key = os.getenv("GMS_KEY")
    if not gms_key:
        raise ValueError("GMS_KEY가 .env에서 로드되지 않았습니다.")
    return ChatOpenAI(
        model=os.getenv("OPENAI_MODEL", "gpt-4o-mini"),
        temperature=0.2,
        base_url="https://gms.ssafy.io/gmsapi/api.openai.com/v1",
        api_key=gms_key,
        request_timeout=30.0,
    )
