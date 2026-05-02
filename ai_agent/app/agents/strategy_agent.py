import json
from typing import Any

from langchain_core.exceptions import OutputParserException
from langchain_core.output_parsers import PydanticOutputParser
from langchain_core.prompts import ChatPromptTemplate

from app.config.llm import strategy_llm
from app.state.investment_state import InvestmentAgentState
from app.state.schemas import StrategyDraft


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

    parser = PydanticOutputParser(pydantic_object=StrategyDraft)

    prompt = ChatPromptTemplate.from_messages(
        [
            (
                "system",
                """
당신은 투자 전략 초안을 생성하는 Strategy Agent입니다.

역할:
- Analysis Layer의 정량 분석 결과를 해석합니다.
- Memory Agent가 제공한 사용자 성향, 정책, 과거 거래 문맥을 반영합니다.
- 최종 매매 확정이 아니라 Critic Agent와 Supervisor Agent가 검토할 전략 초안을 생성합니다.

중요 원칙:
1. 과도하게 공격적인 전략을 만들지 마세요.
2. 사용자 투자 성향과 정책 제약을 우선하세요.
3. 최근 유사 손실 거래가 있으면 진입 금액과 신뢰도를 보수적으로 설정하세요.
4. 정책상 충돌이 있거나 근거가 부족하면 side는 "hold"로 설정하세요.
5. reason에는 기술적 신호, 뉴스 감성, 과거 유사 사례, 사용자 성향을 함께 요약하세요.
6. 반드시 아래 출력 형식을 지키세요.

{format_instructions}
""",
            ),
            (
                "human",
                """
아래 정보를 바탕으로 투자 전략 초안을 생성하세요.

[후보 종목]
{candidate_assets}

[시장 분석 결과]
{analysis_snapshot}

[포트폴리오 상태]
{portfolio_snapshot}

[사용자 투자 성향]
{user_context}

[서비스 정책 및 하드 제약]
{policy_context}

[최근 유사 거래 및 손실 거래 문맥]
{memory_context}

[과거 복기 및 지표 해석 문맥]
{history_context}
""",
            ),
        ]
    )

    chain = prompt | strategy_llm | parser

    inputs = {
        "candidate_assets": _to_json(state.candidate_assets),
        "analysis_snapshot": _to_json(state.analysis_snapshot),
        "portfolio_snapshot": _to_json(state.portfolio_snapshot),
        "user_context": _to_json(state.user_context),
        "policy_context": _to_json(state.policy_context),
        "memory_context": _to_json(state.memory_context),
        "history_context": _to_json(state.history_context),
        "format_instructions": parser.get_format_instructions(),
    }

    try:
        strategy_draft = chain.invoke(inputs)
    except OutputParserException:
        try:
            strategy_draft = chain.invoke(inputs)
        except OutputParserException as exc:
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