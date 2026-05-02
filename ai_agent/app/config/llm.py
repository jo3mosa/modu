import os
from pathlib import Path

from dotenv import load_dotenv
from langchain_openai import ChatOpenAI


env_path = Path(__file__).resolve().parents[2] / ".env"
load_dotenv(env_path)

gms_key = os.getenv("GMS_KEY")

if not gms_key:
    raise ValueError("GMS_KEY가 .env에서 로드되지 않았습니다.")

strategy_llm = ChatOpenAI(
    model=os.getenv("OPENAI_MODEL", "gpt-4o-mini"),
    temperature=0.2,
    base_url="https://gms.ssafy.io/gmsapi/api.openai.com/v1",
    api_key=gms_key,
)