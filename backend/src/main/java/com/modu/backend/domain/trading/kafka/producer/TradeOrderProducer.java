package com.modu.backend.domain.trading.kafka.producer;

import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.error.CommonErrorCode;
import com.modu.backend.global.kafka.constant.KafkaTopic;
import com.modu.backend.global.kafka.dto.TradeOrderExecutedMessage;
import com.modu.backend.global.kafka.dto.TradeOrderMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 거래 주문 Kafka 발행자
 *
 * 발행 토픽:
 *  - trade.order.submitted : 주문 의도 (수동/AI/손절익절 통합 진입점)
 *  - trade.order.executed  : 체결 통보 (단건 체결 단위)
 *
 * [동기 발행 정책]
 * .send(...).get(SEND_TIMEOUT_SECONDS) 로 브로커 ACK 대기.
 * 실패 시 ApiException 으로 변환해 호출 측 @Transactional 롤백을 유도.
 * (DB 저장 → Kafka 발행 순서이므로, 발행 실패 시 DB 상태도 함께 롤백되어 불일치 방지)
 *
 * DLQ 적재는 후순위(별도 이슈)로 분리.
 *
 * [파티션 키 정책]
 * 모든 메시지의 파티션 키 = userId 문자열.
 * 같은 사용자의 주문 순서를 같은 파티션 안에서 보장하기 위함.
 *
 * [traceId 전파]
 * MDC 의 traceId 를 x-trace-id Kafka 헤더로 자동 주입.
 * 후속 Consumer 가 동일 traceId 로 로깅하면 발행~소비 흐름을 단일 traceId 로 추적 가능.
 */
@Slf4j
@Component
public class TradeOrderProducer {

    private static final String TRACE_ID_MDC_KEY     = "traceId";
    private static final String TRACE_ID_HEADER_NAME = "x-trace-id";
    private static final long   SEND_TIMEOUT_SECONDS = 10L;

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public TradeOrderProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * 주문 의도 발행 (trade.order.submitted)
     *
     * @param message 주문 메시지
     */
    public void publishOrderSubmitted(TradeOrderMessage message) {
        publishSync(
                KafkaTopic.TRADE_ORDER_SUBMITTED,
                partitionKey(message.userId()),
                message,
                "orderId=" + message.orderId() + " source=" + message.source()
        );
    }

    /**
     * 체결 통보 발행 (trade.order.executed)
     *
     * @param message 체결 메시지
     */
    public void publishOrderExecuted(TradeOrderExecutedMessage message) {
        publishSync(
                KafkaTopic.TRADE_ORDER_EXECUTED,
                partitionKey(message.userId()),
                message,
                "orderId=" + message.orderId() + " kisOrderNo=" + message.kisOrderNo()
                        + " isFinalFill=" + message.isFinalFill()
        );
    }

    /**
     * 공통 동기 발행 로직
     *
     * @param topic       토픽 이름
     * @param key         파티션 키
     * @param payload     메시지 본문
     * @param logSummary  성공/실패 로그에 추가할 요약 정보
     */
    private void publishSync(String topic, String key, Object payload, String logSummary) {
        ProducerRecord<String, Object> record = new ProducerRecord<>(topic, key, payload);
        injectTraceIdHeader(record);

        try {
            kafkaTemplate.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("Kafka 발행 성공 - topic: {}, key: {}, {}", topic, key, logSummary);

        } catch (InterruptedException e) {
            // 인터럽트 상태 복원 후 예외 전파
            Thread.currentThread().interrupt();
            log.error("Kafka 발행 인터럽트 - topic: {}, key: {}, {}", topic, key, logSummary, e);
            throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR, e);

        } catch (ExecutionException e) {
            // 브로커 측 발행 실패 — 원인 예외(cause) 보존
            log.error("Kafka 발행 실패 - topic: {}, key: {}, {}", topic, key, logSummary, e);
            throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR, e);

        } catch (TimeoutException e) {
            log.error("Kafka 발행 타임아웃({}s) - topic: {}, key: {}, {}",
                    SEND_TIMEOUT_SECONDS, topic, key, logSummary, e);
            throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR, e);
        }
    }

    /**
     * MDC 의 traceId 를 x-trace-id 헤더로 주입
     * traceId 없는 컨텍스트(스케줄러 등) 에서는 헤더 누락 — 정상 흐름
     */
    private void injectTraceIdHeader(ProducerRecord<String, Object> record) {
        String traceId = MDC.get(TRACE_ID_MDC_KEY);
        if (traceId == null || traceId.isBlank()) return;
        record.headers().add(TRACE_ID_HEADER_NAME, traceId.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 파티션 키 생성 — userId 문자열
     * null 방어 (이론상 발생 X, 안전망)
     */
    private String partitionKey(Long userId) {
        return userId == null ? "unknown" : userId.toString();
    }
}
