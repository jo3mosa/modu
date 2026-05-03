import json
from typing import Any
from pydantic import BaseModel


def to_json(value: Any) -> str:
    """
    LLM prompt용 JSON 직렬화 공통 함수
    """

    if isinstance(value, BaseModel):
        value = value.model_dump(mode="json")

    return json.dumps(value, ensure_ascii=False, indent=2, default=str)