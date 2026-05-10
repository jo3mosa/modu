"""
LangSmith 측정 인프라 헬퍼.

역할:
- 그래프 노드 실행 단위에 도메인 metadata(memory_backend, debate_round, winning_side,
  risk_level, history_context_tokens 등)를 태깅한다.
- LangSmith 비활성화(LANGCHAIN_TRACING_V2 미설정 / false) 시에도 모든 함수는 noop으로
  안전하게 동작한다 — 측정 인프라는 운영을 막지 않는다.

활성화 방법:
- .env 에 다음을 설정한다.
  LANGCHAIN_TRACING_V2=true
  LANGCHAIN_API_KEY=...
  LANGCHAIN_PROJECT=modu-mvp
- langchain SDK가 자동으로 ChatOpenAI 호출 trace를 LangSmith에 보낸다.
- 본 모듈은 그 위에 도메인 metadata를 추가로 부여한다.
"""
from __future__ import annotations

import os
from functools import lru_cache
from typing import Any


def is_tracing_enabled() -> bool:
    """LANGCHAIN_TRACING_V2 가 명시적으로 활성화되어 있는지."""
    value = os.getenv("LANGCHAIN_TRACING_V2", "").strip().lower()
    return value in ("true", "1", "yes")


def add_run_metadata(metadata: dict[str, Any]) -> None:
    """
    현재 LangSmith run에 도메인 metadata를 추가한다.

    LangSmith가 비활성이거나 run 컨텍스트 외부면 noop.
    노드 함수 안에서 한 줄 호출로 사용:

        add_run_metadata({"node": "bull_researcher", "round": 1})
    """
    if not is_tracing_enabled():
        return

    try:
        from langsmith.run_helpers import get_current_run_tree

        run = get_current_run_tree()
        if run is None:
            return

        # run.metadata 가 없을 수도 있어 setdefault 처리
        existing = getattr(run, "metadata", None)
        if existing is None:
            run.metadata = dict(metadata)
        else:
            existing.update(metadata)
    except Exception:
        # 측정 인프라 실패는 운영을 막지 않는다.
        return


@lru_cache(maxsize=1)
def _get_token_encoder():
    """tiktoken 인코더 1회 로드."""
    try:
        import tiktoken
    except ImportError:
        return None

    model = os.getenv("OPENAI_MODEL", "gpt-4o-mini")
    try:
        return tiktoken.encoding_for_model(model)
    except KeyError:
        return tiktoken.get_encoding("cl100k_base")


def count_tokens(text: str | None) -> int:
    """
    문자열의 토큰 수를 반환한다. tiktoken 미설치 / 인코딩 실패 시 0.

    실험 1(LLM-Wiki vs DB) 비교에서 history_context 토큰 수 측정에 사용.
    """
    if not text:
        return 0

    encoder = _get_token_encoder()
    if encoder is None:
        return 0

    try:
        return len(encoder.encode(text))
    except Exception:
        return 0
