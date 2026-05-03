from pathlib import Path
from typing import Any

from langchain_core.exceptions import OutputParserException
from langchain_core.output_parsers import PydanticOutputParser
from langchain_core.prompts import ChatPromptTemplate

from app.config.llm import get_strategy_llm
from app.state.investment_state import InvestmentAgentState
from app.state.schemas import CriticFeedback
from app.utils.json_utils import to_json


_PROMPT_PATH = Path(__file__).resolve().parents[2] / "config" / "prompts" / "critic_agent.txt"

_parser = PydanticOutputParser(pydantic_object=CriticFeedback)


def _load_prompt() -> ChatPromptTemplate:
    """
    critic_agent.txt 파일을 읽어 LangChain ChatPromptTemplate으로 변환한다.

    프롬프트 파일은 아래 형식을 따른다.

    [SYSTEM]
    Critic Agent의 역할, 검증 기준, 출력 규칙

    [HUMAN]
    실제 state 값이 주입되는 입력 템플릿
    """

    try:
        text = _PROMPT_PATH.read_text(encoding="utf-8")
    except FileNotFoundError as exc:
        raise FileNotFoundError(
            f"프롬프트 파일을 찾을 수 없습니다: {_PROMPT_PATH}"
        ) from exc

    if "[HUMAN]" not in text:
        raise ValueError(
            f"프롬프트 파일에 [HUMAN] 구분자가 없습니다: {_PROMPT_PATH}"
        )

    system_text, human_text = text.split("[HUMAN]", 1)
    system_text = system_text.replace("[SYSTEM]", "").strip()

    return ChatPromptTemplate.from_messages([
        ("system", system_text),
        ("human", human_text.strip()),
    ])


_prompt = _load_prompt()


def critic_agent(state: InvestmentAgentState) -> dict[str, Any]:
    """
    Critic Agent.

    역할:
    - Strategy Agent가 생성한 strategy_draft를 리스크 관점에서 검토한다.
    - 변동성 과열, 최근 급등락 진입, 사용자 리스크 설정 충돌,
      투자 성향 불일치, 과도한 집중 투자 가능성을 점검한다.
    - LLM 검토 전에 코드 레벨 사전 검증을 먼저 수행한다.

    입력:
    - strategy_draft
    - analysis_snapshot
    - portfolio_snapshot
    - user_context
    - policy_context
    - memory_context

    출력:
    - critic_feedback
    """

    strategy_draft = state.strategy_draft

    # Strategy Agent가 실패했거나 전략 초안이 없으면 Critic은 승인하지 않는다.
    if strategy_draft is None:
        return {
            "critic_feedback": CriticFeedback(
                approved=False,
                risk_level="high",
                comments=[
                    "strategy_draft가 없어 전략 검토를 수행할 수 없습니다.",
                    "전략 초안 생성 실패 상태에서는 최종 투자 결정을 보류해야 합니다.",
                ],
            )
        }

    # side == hold이면 실제 주문이 발생하지 않으므로 승인하되,
    # Supervisor가 보수적 판단으로 이어갈 수 있게 low risk로 반환한다.
    if _get_value(strategy_draft, "side") == "hold":
        return {
            "critic_feedback": CriticFeedback(
                approved=True,
                risk_level="low",
                comments=[
                    "Strategy Agent가 hold를 선택했으므로 신규 주문 리스크는 낮습니다.",
                    "현재 조건에서는 추가 매수/매도보다 관망 판단이 우선됩니다.",
                ],
            )
        }

    # 코드 레벨에서 먼저 확인 가능한 하드 리스크를 점검한다.
    deterministic_comments, has_blocking_risk = _run_deterministic_checks(state)

    if has_blocking_risk:
        return {
            "critic_feedback": CriticFeedback(
                approved=False,
                risk_level="high",
                comments=deterministic_comments,
            )
        }

    chain = _prompt | get_strategy_llm() | _parser

    inputs = {
        "strategy_draft": to_json(strategy_draft),
        "analysis_snapshot": to_json(state.analysis_snapshot),
        "portfolio_snapshot": to_json(state.portfolio_snapshot),
        "user_context": to_json(state.user_context),
        "policy_context": to_json(state.policy_context),
        "memory_context": to_json(state.memory_context),
        "deterministic_comments": to_json(deterministic_comments),
        "format_instructions": _parser.get_format_instructions(),
    }

    try:
        critic_feedback = chain.invoke(inputs)

    except OutputParserException:
        try:
            critic_feedback = chain.invoke(inputs)
        except OutputParserException as exc:
            return _fallback_feedback(
                reason="LLM 출력 파싱 2회 실패",
                detail=str(exc),
                comments=deterministic_comments,
            )

    except Exception as exc:
        return _fallback_feedback(
            reason="LLM 호출 실패",
            detail=str(exc),
            comments=deterministic_comments,
        )

    # LLM이 사전 검증 결과를 누락할 수 있으므로 comments에 병합한다.
    merged_comments = list(dict.fromkeys([
        *deterministic_comments,
        *critic_feedback.comments,
    ]))

    return {
        "critic_feedback": CriticFeedback(
            approved=critic_feedback.approved,
            risk_level=critic_feedback.risk_level,
            comments=merged_comments,
        )
    }


def _run_deterministic_checks(state: InvestmentAgentState) -> list[str]:
    """
    LLM 호출 전 코드로 먼저 검증할 수 있는 리스크를 점검한다.

    목적:
    - 명확한 정책 위반을 LLM 판단에만 맡기지 않기 위함
    - Supervisor가 참고할 수 있는 객관적 검토 근거를 남기기 위함
    """

    comments: list[str] = []

    strategy_draft = state.strategy_draft
    policy_context = state.policy_context or {}
    portfolio_snapshot = state.portfolio_snapshot or {}
    user_context = state.user_context or {}

    side = _get_value(strategy_draft, "side")
    asset = _get_value(strategy_draft, "asset")
    order_amount = _get_value(strategy_draft, "order_amount") or 0

    system_constraints = policy_context.get("system_trading_constraints", {})
    max_single_stock_ratio = system_constraints.get("resolved_max_single_stock_ratio")

    total_value = (
        portfolio_snapshot.get("total_value")
        or portfolio_snapshot.get("total_asset")
        or portfolio_snapshot.get("evaluation_amount")
        or 0
    )

    if side in {"buy", "sell"} and not asset:
        comments.append("매수/매도 전략인데 대상 종목 asset이 비어 있습니다.")

    if side == "buy" and order_amount <= 0:
        comments.append("매수 전략인데 주문 금액이 0 이하입니다.")

    if side == "buy" and max_single_stock_ratio and total_value:
        order_ratio = order_amount / total_value

        if order_ratio > max_single_stock_ratio:
            comments.append(
                f"주문 비중이 사용자 성향 기준 단일 종목 최대 비중을 초과합니다. "
                f"order_ratio={order_ratio:.2%}, limit={max_single_stock_ratio:.2%}"
            )

    risk_rules = user_context.get("risk_rules", {})
    stop_loss_price = _get_value(strategy_draft, "stop_loss_price")
    target_price = _get_value(strategy_draft, "target_price")

    if side == "buy" and stop_loss_price is None:
        comments.append("매수 전략인데 손절가가 설정되지 않았습니다.")

    if side == "buy" and target_price is None:
        comments.append("매수 전략인데 목표가가 설정되지 않았습니다.")

    if risk_rules:
        comments.append("사용자 risk_rules 기준과 전략의 손절/익절 구조를 추가 검토해야 합니다.")

    if not comments:
        comments.append("코드 레벨 사전 검증에서 즉시 차단할 하드 리스크는 발견되지 않았습니다.")

    return comments


def _fallback_feedback(
    reason: str,
    detail: str,
    comments: list[str],
) -> dict[str, Any]:
    """
    LLM 기반 Critic 검토가 실패했을 때 보수적인 피드백을 반환한다.
    """

    return {
        "critic_feedback": CriticFeedback(
            approved=False,
            risk_level="high",
            comments=[
                f"Critic Agent 검토 실패: {reason}",
                detail,
                *comments,
                "리스크 검토가 정상 완료되지 않았으므로 최종 투자 결정은 보류하는 것이 안전합니다.",
            ],
        )
    }


def _get_value(obj: Any, key: str) -> Any:
    """
    dict와 Pydantic 모델을 모두 지원하는 값 조회 함수.
    """

    if obj is None:
        return None

    if isinstance(obj, dict):
        return obj.get(key)

    return getattr(obj, key, None)
