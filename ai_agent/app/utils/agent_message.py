import logging
from datetime import datetime, timedelta, timezone

from app.config.kafka import KafkaTopic, get_kafka_producer
from app.state.investment_state import InvestmentAgentState

logger = logging.getLogger(__name__)

_KST = timezone(timedelta(hours=9))


def publish_agent_message(
    state: InvestmentAgentState,
    agent: str,
    seq: int,
    text: str,
) -> None:
    """각 에이전트 노드 발화를 ai.agent.message 토픽으로 발행한다.

    실패해도 에이전트 파이프라인에 영향을 주지 않는다.
    """
    candidate_assets = state.candidate_assets or []
    if not candidate_assets:
        logger.warning("publish_agent_message: candidate_assets 없음 — 발행 건너뜀 (agent=%s, seq=%d)", agent, seq)
        return

    stock_code = candidate_assets[0].get("stock_code") or candidate_assets[0].get("ticker")
    if not stock_code:
        logger.warning("publish_agent_message: stock_code 없음 — 발행 건너뜀 (agent=%s, seq=%d)", agent, seq)
        return

    payload = {
        "user_id": state.user_id,
        "stock_code": stock_code,
        "judgment_id": None,
        "agent": agent,
        "seq": seq,
        "text": text,
        "created_at": datetime.now(_KST).isoformat(),
    }

    try:
        producer = get_kafka_producer()
        producer.send(
            KafkaTopic.AI_AGENT_MESSAGE,
            key=str(state.user_id),
            value=payload,
        )
        producer.flush()
        logger.debug("ai.agent.message published: user_id=%s, agent=%s, seq=%d", state.user_id, agent, seq)
    except Exception as exc:
        logger.error("ai.agent.message 발행 실패 (agent=%s, seq=%d): %s", agent, seq, exc)
