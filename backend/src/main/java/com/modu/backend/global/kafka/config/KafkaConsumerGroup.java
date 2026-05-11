package com.modu.backend.global.kafka.constant;

/**
 * Kafka Consumer Group ID 상수 관리
 * - 그룹 ID가 다르면 같은 토픽을 구독해도 서로 독립적으로 처리됨
 */
public class KafkaConsumerGroup {

    // trade.order.submitted 소비 → KIS API 주문 실행 (concurrency=1, 순차 처리)
    public static final String KIS_ORDER        = "kis-order-group";

    // trade.order.executed 소비 → 체결 DB 처리 및 포트폴리오 업데이트
    public static final String PORTFOLIO_UPDATE = "portfolio-update-group";

    // ai.decision.generated 소비 → AI 판단 저장 + 자동 주문 발행
    public static final String AI_DECISION      = "backend-ai-judgment-consumer";
}
