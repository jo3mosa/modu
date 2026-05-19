import logging
from datetime import datetime, timedelta, timezone

from app.config.kafka import KafkaTopic, get_kafka_producer
from app.context.memory_context import extract_key_signals
from app.graph.builder import GraphMode, build_investment_graph
from app.triggers.schemas import UserTriggerEvent
from app.triggers.state_factory import build_state_from_user_trigger

logger = logging.getLogger(__name__)

_KST = timezone(timedelta(hours=9))

_graph_cache: dict[GraphMode, object] = {}


def _get_graph(mode: GraphMode = "debate_1"):
    if mode not in _graph_cache:
        _graph_cache[mode] = build_investment_graph(mode=mode)
    return _graph_cache[mode]


def run_pipeline(event: UserTriggerEvent, mode: GraphMode = "debate_1") -> dict:
    """UserTriggerEvent를 받아 LangGraph 파이프라인을 실행하고 최종 state를 반환한다.

    mode: 'debate_0' (토론 없음, ablation) / 'debate_1' (Bull/Bear 1R, 기본/실시간) /
          'debate_2' (Bull/Bear 2R, ablation).
    """
    state = build_state_from_user_trigger(event)
    return _get_graph(mode).invoke(state)


def _build_decision_payload(event: UserTriggerEvent, result: dict) -> dict:
    """BE와 합의된 ai.decision.generated 페이로드를 구성한다."""
    final_decision = result.get("final_decision")
    research_verdict = result.get("research_verdict")
    debate_state = result.get("investment_debate_state") or {}
    analysis_snapshot = event.analysis_snapshot or {}

    # BE 매핑표에 없거나 사용처 미합의인 필드는 명시적으로 제외:
    # - asset: top-level stock_code와 중복
    # - risk_summary / expected_scenario / user_message: ai_judgments 매핑 컬럼 부재
    final_decision_dump = (
        final_decision.model_dump(
            exclude={"asset", "risk_summary", "expected_scenario", "user_message"}
        )
        if final_decision
        else None
    )

    return {
        "user_id": event.user_id,
        "source_event_id": event.event_id,
        "stock_code": event.stock_code,
        "created_at": datetime.now(_KST).isoformat(),

        "final_decision": final_decision_dump,

        "debate": {
            "bull_claim": debate_state.get("bull_history") or None,
            "bear_claim": debate_state.get("bear_history") or None,
            "winner": research_verdict.winning_side if research_verdict else None,
            "key_signals": extract_key_signals(analysis_snapshot),
        },

        "indicators_snapshot": analysis_snapshot,

        "flow_status": result.get("flow_status"),

        # 비보유자(False) BUY 결정은 자동매매 실행이 아니라 매수 추천 알림으로 처리해야 한다.
        # 백엔드는 이 값을 보고 is_holder=False 이면 주문 실행 대신 사용자에게 매수 의향을 묻는다.
        "is_holder": event.is_holder,
    }


def _is_buy_decision(result: dict) -> bool:
    """에이전트 결과가 BUY 결정인지 판별한다."""
    final_decision = result.get("final_decision")
    if final_decision is None:
        return False
    return final_decision.action == "trade" and final_decision.side == "buy"


def run_and_publish(event: UserTriggerEvent) -> None:
    """파이프라인 실행 후 결과를 ai.decision.generated 토픽으로 발행한다.

    비보유자(is_holder=False)의 경우 BUY 결정일 때만 발행한다.
    SELL/HOLD 결정은 보유하지 않은 종목이므로 발행을 생략한다.
    """
    result = run_pipeline(event)

    if not event.is_holder and not _is_buy_decision(result):
        logger.info(
            "비보유자 SELL/HOLD 결과 발행 생략: user_id=%s, stock_code=%s, flow_status=%s",
            event.user_id,
            event.stock_code,
            result.get("flow_status"),
        )
        return

    payload = _build_decision_payload(event, result)

    producer = get_kafka_producer()
    producer.send(
        KafkaTopic.AI_DECISION_GENERATED,
        key=str(event.user_id),
        value=payload,
    )
    producer.flush()

    logger.info(
        "ai.decision.generated published: source_event_id=%s, user_id=%s, stock_code=%s, flow_status=%s",
        event.event_id,
        event.user_id,
        event.stock_code,
        result.get("flow_status"),
    )
