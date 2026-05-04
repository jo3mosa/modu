from functools import lru_cache
from pathlib import Path

from langchain_core.prompts import ChatPromptTemplate


@lru_cache(maxsize=32)
def load_prompt(prompt_path: str) -> ChatPromptTemplate:
    path = Path(prompt_path)

    try:
        text = path.read_text(encoding="utf-8")
    except FileNotFoundError as exc:
        raise FileNotFoundError(
            f"프롬프트 파일을 찾을 수 없습니다: {path}"
        ) from exc

    if "[HUMAN]" not in text:
        raise ValueError(
            f"프롬프트 파일에 [HUMAN] 구분자가 없습니다: {path}"
        )

    system_text, human_text = text.split("[HUMAN]", 1)
    system_text = system_text.replace("[SYSTEM]", "").strip()

    return ChatPromptTemplate.from_messages([
        ("system", system_text),
        ("human", human_text.strip()),
    ])