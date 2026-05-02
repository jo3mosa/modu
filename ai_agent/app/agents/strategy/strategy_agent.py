import json
from pathlib import Path
from typing import Any

from langchain_core.exceptions import OutputParserException
from langchain_core.output_parsers import PydanticOutputParser
from langchain_core.prompts import ChatPromptTemplate

from app.config.llm import strategy_llm
from app.state.investment_state import InvestmentAgentState
from app.state.schemas import StrategyDraft

# Strategy Agent 전용 프롬프트 파일 경로
# 현재 파일 기준:
# app/agents/strategy/strategy_agent.py
# → app/config/prompts/strategy_agent.txt
_PROMPT_PATH = Path(__file__).resolve().parents[2] / "config" / "prompts" / "strategy_agent.txt"


def _load_prompt() -> ChatPromptTemplate:
    text = _PROMPT_PATH.read_text(encoding="utf-8")
    system_text, human_text = text.split("[HUMAN]", 1)
    system_text = system_text.replace("[SYSTEM]", "").strip()
    return ChatPromptTemplate.from_messages([
        ("system", system_text),
        ("human", human_text.strip()),
    ])

# LLM 출력 결과를 StrategyDraft Pydantic 모델로 파싱하기 위한 파서
#
# 역할:
# - LLM이 반환한 JSON 문자열을 StrategyDraft 객체로 변환
# - 필수 필드 누락, 타입 불일치, Literal 값 오류 등을 검증
# - 예: side가 Literal["buy", "sell", "hold"]가 아닌 값이면 파싱 실패
_parser = PydanticOutputParser(pydantic_object=StrategyDraft)

# Strategy Agent 프롬프트를 모듈 로드 시점에 한 번만 읽는다.
#
# 매 요청마다 파일을 읽지 않도록 하여 불필요한 I/O를 줄인다.
# 단, 프롬프트 파일을 수정해도 서버 재시작 전까지는 반영되지 않는다.
_prompt = _load_prompt()

# Strategy Agent 실행 체인
#
# 흐름:
# 1. _prompt: state 값을 LLM 입력 메시지로 변환
# 2. strategy_llm: 투자 전략 초안 생성
# 3. _parser: LLM 출력을 StrategyDraft로 검증 및 변환
_chain = _prompt | strategy_llm | _parser


def strategy_agent(state: InvestmentAgentState) -> dict[str, Any]:
    """
    Strategy Agent.

    역할:
    - Analysis Layer 결과와 Memory Agent 문맥을 기반으로 LLM이 투자 전략 초안을 생성한다.
    - 출력은 반드시 StrategyDraft 스키마를 따른다.

    입력:
    - analysis_snapshot
    - candidate_assets
    - portfolio_snapshot
    - memory_context
    - user_context
    - policy_context
    - history_context

    출력:
    - strategy_draft
    """

    # 모듈 레벨에서 생성한 chain을 사용한다.
    # 현재는 단순 대입이지만, 추후 agent별 chain 교체나 테스트 mock 주입 시 확장 가능
    chain = _chain

    # LLM 프롬프트에 주입할 입력값 구성
    inputs = {
        "candidate_assets": _to_json(state.candidate_assets),
        "analysis_snapshot": _to_json(state.analysis_snapshot),
        "portfolio_snapshot": _to_json(state.portfolio_snapshot),
        "user_context": _to_json(state.user_context),
        "policy_context": _to_json(state.policy_context),
        "memory_context": _to_json(state.memory_context),
        "history_context": _to_json(state.history_context),
        "format_instructions": _parser.get_format_instructions(),
    }

    try:
        # 1차 LLM 호출
        # 정상적으로 동작하면 strategy_draft는 StrategyDraft 객체로 반환
        strategy_draft = chain.invoke(inputs)
    except OutputParserException:
        # LLM이 잘못된 JSON을 반환하거나,
        # StrategyDraft 스키마에 맞지 않는 값을 반환한 경우 여기로 들어온다.
        try:
            # 2차 LLM 재호출
            strategy_draft = chain.invoke(inputs)
        except OutputParserException as exc:
            # 2회 모두 파싱 실패한 경우:
            # 전략 생성을 강행하지 않고 flow_status를 hold로 설정한다.
            return {
                "flow_status": "hold",
                "error_context": {
                    "agent": "strategy_agent",
                    "reason": "LLM 출력 파싱 2회 실패",
                    "detail": str(exc),
                },
            }

    return {
        "strategy_draft": strategy_draft
    }


def _to_json(value: Any) -> str:
    """
    LLM prompt에 넣기 위한 JSON 직렬화 함수.
    한글이 깨지지 않도록 ensure_ascii=False 사용.
    """

    if value is None:
        return "{}"

    return json.dumps(
        value,
        ensure_ascii=False,
        indent=2,
        default=str,
    )