from typing import Any


def get_value(obj: Any, key: str) -> Any:
    """
    dict와 Pydantic 모델 객체를 모두 지원하는 안전한 값 조회 함수.

    Agent 간 state 값이 dict 또는 Pydantic 객체 형태로 섞여 들어와도
    동일한 방식으로 값을 조회하기 위해 사용한다.
    """

    if obj is None:
        return None

    if isinstance(obj, dict):
        return obj.get(key)

    return getattr(obj, key, None)