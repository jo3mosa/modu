package com.modu.backend.global.kafka.constant;

/**
 * Kafka Consumer Group ID 상수
 *
 * 그룹 단위로 파티션 오프셋이 관리되므로, 동일 토픽을 다른 책임으로 소비하는 경우 그룹 분리 필수.
 * 운영 환경 토픽 변경 없이 컨슈머 그룹만 재시작 시 동일 오프셋부터 재개 가능.
 *
 * 그룹 명세:
 *  - KIS_ORDER         : trade.order.submitted 소비 → KIS 주문 호출 (S14P31B106-306)
 *  - PORTFOLIO_UPDATE  : trade.order.executed  소비 → 체결 처리·포트폴리오 갱신 (S14P31B106-291)
 *  - AI_DECISION       : ai.decision.generated 소비 → AI 판단 → 주문 발행 (S14P31B106-263)
 */
public final class KafkaConsumerGroup {

    public static final String KIS_ORDER        = "backend-kis-order-consumer";
    public static final String PORTFOLIO_UPDATE = "backend-portfolio-update-consumer";
    public static final String AI_DECISION      = "backend-ai-decision-consumer";

    private KafkaConsumerGroup() {}
}
