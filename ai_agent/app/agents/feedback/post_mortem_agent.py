import logging
from pathlib import Path

from langchain_core.exceptions import OutputParserException
from langchain_core.output_parsers import PydanticOutputParser
from langchain_core.runnables import Runnable

from app.config.llm import get_strategy_llm
from app.feedback.schemas import PostMortemReflection
from app.observability.langsmith_helpers import add_run_metadata
from app.utils.json_utils import to_json
from app.utils.prompt_loader import load_prompt

logger = logging.getLogger(__name__)

_PROMPT_PATH = Path(__file__).resolve().parents[2] / "config" / "prompts" / "post_mortem_agent.txt"

_parser = PydanticOutputParser(pydantic_object=PostMortemReflection)


def _build_chain() -> Runnable:
    """
    Reflection 체인 구성을 단일 함수로 분리한다.
    테스트에서 이 함수를 monkeypatch해 LLM 호출 없이 검증한다.
    """
    return load_prompt(str(_PROMPT_PATH)) | get_strategy_llm() | _parser


def post_mortem_agent(
    decision_content: str,
    raw_return: float,
    alpha_return: float,
    holding_days: int,
    risk_level: str | None = None,
    key_signals: list[str] | None = None,
) -> PostMortemReflection | None:
    """
    Post-Mortem Agent.

    역할:
    - 청산된 거래의 과거 결정과 실제 결과(raw_return / alpha_return / holding_days)를
      받아 회고 리포트를 생성한다.
    - 출력은 PostMortemReflection (Pydantic 구조화). pipeline이 DB로 영속화한다.

    실패 시 정책:
    - 출력 파싱 또는 LLM 호출 실패 시 None 반환. 회고는 머니패스가 아니므로 silent skip이 안전.
    - 다만 logger.warning / logger.exception으로 운영 가시성은 확보한다.
    """

    chain = _build_chain()

    inputs = {
        "decision_content": decision_content,
        "raw_return": f"{raw_return:+.2%}",
        "alpha_return": f"{alpha_return:+.2%}",
        "holding_days": holding_days,
        "risk_level": risk_level or "(미상)",
        "key_signals": to_json(key_signals or []),
        "format_instructions": _parser.get_format_instructions(),
    }

    add_run_metadata({
        "node": "post_mortem_agent",
        "raw_return": raw_return,
        "alpha_return": alpha_return,
        "holding_days": holding_days,
    })

    try:
        return chain.invoke(inputs)
    except OutputParserException:
        # 1회 재시도. decision_manager / strategy_manager와 동일 패턴.
        try:
            return chain.invoke(inputs)
        except OutputParserException as exc:
            logger.warning("post_mortem_agent: 출력 파싱 2회 실패, skip — %s", exc)
            return None
    except Exception:
        logger.exception("post_mortem_agent: LLM 호출 실패, skip")
        return None
