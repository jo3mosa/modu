package com.modu.backend.domain.ai.kafka.consumer;

import com.modu.backend.domain.ai.exception.AiErrorCode;
import com.modu.backend.domain.ai.service.SignalHandlerService;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.kafka.dto.AiDecisionMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AiDecisionConsumerTest {

    @Mock SignalHandlerService signalHandlerService;
    @Mock Acknowledgment ack;

    @InjectMocks
    AiDecisionConsumer consumer;

    private final AiDecisionMessage message = new AiDecisionMessage(
            1L, "evt-1", "005930", OffsetDateTime.now(), null,
            null, null, null, "completed"
    );

    @Test
    @DisplayName("정상 처리 시 ack 호출")
    void successAcks() {
        consumer.onMessage(message, ack);

        verify(signalHandlerService).handle(message);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("비즈니스 예외(ApiException) 시 ack — 격리하고 다음으로 진행")
    void businessExceptionAcks() {
        doThrow(new ApiException(AiErrorCode.INVALID_DECISION_MESSAGE))
                .when(signalHandlerService).handle(any());

        consumer.onMessage(message, ack);

        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("시스템 예외(RuntimeException) 시 ack 안 함 + 예외 전파 — 재시도 유도")
    void systemExceptionRethrows() {
        doThrow(new RuntimeException("DB down"))
                .when(signalHandlerService).handle(any());

        assertThatThrownBy(() -> consumer.onMessage(message, ack))
                .isInstanceOf(RuntimeException.class);

        verify(ack, never()).acknowledge();
    }
}
