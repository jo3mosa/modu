package com.modu.backend.domain.trading.execution.producer;

import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.error.CommonErrorCode;
import com.modu.backend.global.kafka.constant.KafkaTopic;
import com.modu.backend.global.kafka.dto.TradeSettledMessage;
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
 * trade.settled 토픽 Kafka 발행자 — S14P31B106-291
 *
 * TradeOrderProducer 와 분리한 이유:
 *  - settled 는 회고 Agent 트리거 전용 — 토픽/책임 분리로 추후 변경 시 영향 범위 축소
 *  - 발행 정책 (동기 + 10s timeout) 은 TradeOrderProducer 와 동일 패턴 차용
 *
 * [파티션 키]
 *  userId — 같은 사용자의 회고 메시지 순서 보장
 */
@Slf4j
@Component
public class TradeSettledProducer {

    private static final String TRACE_ID_MDC_KEY     = "traceId";
    private static final String TRACE_ID_HEADER_NAME = "x-trace-id";
    private static final long   SEND_TIMEOUT_SECONDS = 10L;

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public TradeSettledProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(TradeSettledMessage message) {
        ProducerRecord<String, Object> record = new ProducerRecord<>(
                KafkaTopic.TRADE_SETTLED,
                partitionKey(message.userId()),
                message);
        injectTraceIdHeader(record);

        String summary = "eventId=" + message.eventId() + " tradePnlRecordId=" + message.tradePnlRecordId();
        try {
            kafkaTemplate.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("Kafka 발행 성공 - topic: {}, {}", KafkaTopic.TRADE_SETTLED, summary);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Kafka 발행 인터럽트 - topic: {}, {}", KafkaTopic.TRADE_SETTLED, summary, e);
            throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR, e);
        } catch (ExecutionException e) {
            log.error("Kafka 발행 실패 - topic: {}, {}", KafkaTopic.TRADE_SETTLED, summary, e);
            throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR, e);
        } catch (TimeoutException e) {
            log.error("Kafka 발행 타임아웃({}s) - topic: {}, {}",
                    SEND_TIMEOUT_SECONDS, KafkaTopic.TRADE_SETTLED, summary, e);
            throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR, e);
        } catch (RuntimeException e) {
            log.error("Kafka 발행 동기 실패 - topic: {}, {}", KafkaTopic.TRADE_SETTLED, summary, e);
            throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR, e);
        }
    }

    private void injectTraceIdHeader(ProducerRecord<String, Object> record) {
        String traceId = MDC.get(TRACE_ID_MDC_KEY);
        if (traceId == null || traceId.isBlank()) return;
        record.headers().add(TRACE_ID_HEADER_NAME, traceId.getBytes(StandardCharsets.UTF_8));
    }

    private String partitionKey(Long userId) {
        return userId == null ? "unknown" : userId.toString();
    }
}
