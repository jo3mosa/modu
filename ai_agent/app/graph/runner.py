import logging

from app.config.kafka import KafkaTopic, get_kafka_producer
from app.graph.builder import build_investment_graph
from app.triggers.schemas import UserTriggerEvent
from app.triggers.state_factory import build_state_from_user_trigger

logger = logging.getLogger(__name__)

_graph = None


def _get_graph():
    global _graph
    if _graph is None:
        _graph = build_investment_graph()
    return _graph


def run_pipeline(event: UserTriggerEvent) -> dict:
    """UserTriggerEvent를 받아 LangGraph 파이프라인을 실행하고 최종 state를 반환한다."""
    state = build_state_from_user_trigger(event)
    return _get_graph().invoke(state)


def run_and_publish(event: UserTriggerEvent) -> None:
    """파이프라인 실행 후 결과를 ai.decision.generated 토픽으로 발행한다."""
    result = run_pipeline(event)

    final_decision = result.get("final_decision")
    payload = {
        "user_id": event.user_id,
        "source_event_id": event.event_id,
        "stock_code": event.stock_code,
        "final_decision": final_decision.model_dump() if final_decision else None,
        "flow_status": result.get("flow_status"),
    }

    producer = get_kafka_producer()
    producer.send(
        KafkaTopic.AI_DECISION_GENERATED,
        key=str(event.user_id),
        value=payload,
    )
    producer.flush()

    logger.info(
        "ai.decision.generated published: user_id=%s, stock_code=%s, flow_status=%s",
        event.user_id,
        event.stock_code,
        result.get("flow_status"),
    )
