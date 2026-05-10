from pydantic import ValidationError

from app.config.kafka import KafkaTopic, get_kafka_producer
from app.triggers.schemas import MarketTriggerEvent, TriggerType, UserTriggerEvent


def produce_mock_market_signal() -> None:
    """
    market.signal.detected 토픽에 테스트용 MarketTriggerEvent를 발행한다.

    실행 전 Redis에 포지션 데이터가 있어야 사용자 매칭이 동작한다:
        SADD position:index:stock:005930 1 2
    """
    event = MarketTriggerEvent(
        event_id="mock-market-event-001",
        trigger_type=TriggerType.MARKET_EVENT,
        trigger_reason=["RSI 과매도 구간 진입", "거래량 급증"],
        stock_code="005930",
        market_snapshot={
            "market": "KOSPI",
            "market_status": "OPEN",
            "index_change_rate": -0.8,
        },
        analysis_snapshot={
            "stock_code": "005930",
            "rsi": 28.5,
            "macd_signal": "BUY",
            "volume_change_rate": 180.0,
        },
        candidate_assets=[
            {
                "stock_code": "005930",
                "stock_name": "삼성전자",
                "current_price": 71000,
            }
        ],
    )

    producer = get_kafka_producer()
    producer.send(
        KafkaTopic.MARKET_SIGNAL_DETECTED,
        key=event.stock_code,
        value=event.model_dump(),
    )
    producer.flush()
    print(f"Produced market signal: {event.event_id} (stock_code={event.stock_code})")


def create_mock_user_trigger() -> UserTriggerEvent:
    """
    LangGraph 실행 테스트를 위한 mock UserTriggerEvent를 생성한다.

    실제 운영에서는 User Trigger Matcher 또는 Position Monitoring이
    UserTriggerEvent를 생성하지만, 현재는 외부 연동 없이
    Reasoning Layer 진입 흐름만 검증하기 위해 mock 데이터를 사용한다.
    """

    return UserTriggerEvent(
        event_id="mock-user-trigger-001",
        source_event_id="mock-market-event-001",
        trigger_type=TriggerType.MARKET_EVENT,
        trigger_reason=[
            "RSI 과매도 구간 진입",
            "거래량 급증",
        ],
        user_id=1,
        stock_code="005930",
        market_snapshot={
            "market": "KOSPI",
            "market_status": "OPEN",
            "index_change_rate": -0.8,
        },
        analysis_snapshot={
            "stock_code": "005930",
            "rsi": 28.5,
            "macd_signal": "BUY",
            "volume_change_rate": 180.0,
        },
        candidate_assets=[
            {
                "stock_code": "005930",
                "stock_name": "삼성전자",
                "current_price": 71000,
            }
        ],
        portfolio_snapshot={
            "cash_balance": 1_000_000,
            "total_assets": 5_000_000,
            "holdings": [
                {
                    "stock_code": "005930",
                    "stock_name": "삼성전자",
                    "quantity": 10,
                    "average_price": 70000,
                    "current_price": 71000,
                    "evaluation_amount": 710000,
                    "profit_rate": 1.43,
                }
            ],
        },
        user_context={
            "risk_profile": "moderate",
            "max_position_ratio": 0.3,
            "stop_loss_rate": -5.0,
            "target_profit_rate": 8.0,
        },
        source="mock_trigger",
    )


def create_invalid_mock_user_trigger():
    """
    필수 필드 누락 시 Pydantic validation이 발생하는지 확인하기 위한 mock.
    user_id, stock_code 등이 없으므로 ValidationError가 발생해야 한다.
    """

    return UserTriggerEvent(
        event_id="invalid-trigger-001",
        trigger_type=TriggerType.MARKET_EVENT,
        trigger_reason=["필수 필드 누락 테스트"],
    )


def validate_invalid_trigger():
    try:
        create_invalid_mock_user_trigger()
    except ValidationError as e:
        print("필수 필드 누락 검증 성공")
        print(e)
        return True

    return False


if __name__ == "__main__":
    produce_mock_market_signal()
