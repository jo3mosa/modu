"""main

1분 주기로 engine 모듈을 순차 실행:
    signal_builder → detection_engine → cooldown_manager → event_publisher

수집 모듈(collectors/*)은 각자의 주기로 Redis를 갱신하므로 main에서 직접 호출하지 않는다.
"""

from engine import cooldown_manager, detection_engine, event_publisher, signal_builder


def run_once(stock_code: str) -> None:
    signal = signal_builder.build(stock_code)
    rule_ids = detection_engine.detect(signal)
    valid = cooldown_manager.filter_active(stock_code, rule_ids)
    if valid:
        event_publisher.publish(stock_code, valid, signal)


def main() -> None:
    raise NotImplementedError("main loop (1-minute scheduler) is not implemented yet")


if __name__ == "__main__":
    main()
