package com.modu.backend.domain.trading.kafka.producer;

import com.modu.backend.domain.trading.entity.OrderSide;
import com.modu.backend.domain.trading.entity.OrderSource;
import com.modu.backend.domain.trading.entity.OrderType;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.error.CommonErrorCode;
import com.modu.backend.global.kafka.constant.KafkaTopic;
import com.modu.backend.global.kafka.dto.TradeOrderExecutedMessage;
import com.modu.backend.global.kafka.dto.TradeOrderMessage;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TradeOrderProducer 단위 테스트
 *
 * KafkaTemplate Mock 기반 — 실제 직렬화·네트워크는 검증하지 않음.
 * 검증 범위: 토픽/파티션키/payload 매핑, x-trace-id 헤더 주입, 발행 실패 → ApiException 변환.
 */
@ExtendWith(MockitoExtension.class)
class TradeOrderProducerTest {

    @Mock
    KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks
    TradeOrderProducer producer;

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    // ── 주문 의도 발행 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("주문 의도 발행 — 토픽/파티션키/payload 정합 + x-trace-id 헤더 주입")
    void 주문_의도_발행_성공() {
        // given
        MDC.put("traceId", "trace-abc");
        stubSendSuccess();
        TradeOrderMessage msg = sampleSubmittedMessage(42L);

        // when
        producer.publishOrderSubmitted(msg);

        // then
        ProducerRecord<String, Object> sent = captureSent();
        assertThat(sent.topic()).isEqualTo(KafkaTopic.TRADE_ORDER_SUBMITTED);
        assertThat(sent.key()).isEqualTo("42");
        assertThat(sent.value()).isSameAs(msg);
        Header traceHeader = sent.headers().lastHeader("x-trace-id");
        assertThat(traceHeader).isNotNull();
        assertThat(new String(traceHeader.value(), StandardCharsets.UTF_8)).isEqualTo("trace-abc");
    }

    @Test
    @DisplayName("MDC traceId 없으면 x-trace-id 헤더 미주입")
    void traceId_없으면_헤더_미주입() {
        // given (MDC 비어있음 — @AfterEach 가 보장)
        stubSendSuccess();
        TradeOrderMessage msg = sampleSubmittedMessage(7L);

        // when
        producer.publishOrderSubmitted(msg);

        // then
        ProducerRecord<String, Object> sent = captureSent();
        assertThat(sent.headers().lastHeader("x-trace-id")).isNull();
    }

    @Test
    @DisplayName("send() 자체가 동기 RuntimeException 던질 때도 EXTERNAL_API_ERROR 로 매핑")
    void send_동기_RuntimeException_변환() {
        // given — send() 호출 시 직렬화 실패·producer 닫힘 등으로 즉시 throw 되는 케이스
        RuntimeException syncFailure = new org.apache.kafka.common.KafkaException("serialization failed");
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenThrow(syncFailure);
        TradeOrderMessage msg = sampleSubmittedMessage(42L);

        // when & then
        assertThatThrownBy(() -> producer.publishOrderSubmitted(msg))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
                    assertThat(ex.getCause()).isSameAs(syncFailure);
                });
    }

    @Test
    @DisplayName("브로커 발행 실패 시 EXTERNAL_API_ERROR + cause 보존")
    void 발행_실패_ApiException_변환() {
        // given
        RuntimeException brokerError = new RuntimeException("broker down");
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(brokerError));
        TradeOrderMessage msg = sampleSubmittedMessage(42L);

        // when & then
        assertThatThrownBy(() -> producer.publishOrderSubmitted(msg))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
                    // cause = ExecutionException(originalCause)
                    assertThat(ex.getCause()).isNotNull();
                });
    }

    // ── 체결 발행 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("체결 발행 — TRADE_ORDER_EXECUTED 토픽 + userId 파티션키")
    void 체결_발행_성공() {
        // given
        stubSendSuccess();
        TradeOrderExecutedMessage msg = TradeOrderExecutedMessage.of(
                100L, "KIS-1", 42L, "005930",
                OrderSide.SELL, 5L, 75000L, 5L, true,
                OffsetDateTime.now()
        );

        // when
        producer.publishOrderExecuted(msg);

        // then
        ProducerRecord<String, Object> sent = captureSent();
        assertThat(sent.topic()).isEqualTo(KafkaTopic.TRADE_ORDER_EXECUTED);
        assertThat(sent.key()).isEqualTo("42");
        assertThat(sent.value()).isSameAs(msg);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void stubSendSuccess() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, Object> captureSent() {
        ArgumentCaptor<ProducerRecord<String, Object>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        return captor.getValue();
    }

    private TradeOrderMessage sampleSubmittedMessage(Long userId) {
        return TradeOrderMessage.of(
                "uuid-" + userId,
                null,
                userId,
                "005930",
                OrderSide.BUY,
                OrderType.LIMIT,
                10L,
                70000L,
                OrderSource.MANUAL,
                null,
                OffsetDateTime.now()
        );
    }
}
