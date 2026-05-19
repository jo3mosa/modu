package com.modu.backend.domain.trading.sse.redis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modu.backend.domain.trading.sse.OrderSseEmitterManager;
import com.modu.backend.domain.trading.sse.OrderSseEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * OrderSseRedisSubscriber 단위 테스트
 *
 * 검증 범위:
 *  - 정상 envelope 수신 → deliverLocal(userId, eventName, payload) 호출
 *  - 비정상 채널 (prefix/숫자 어긋남) — deliverLocal 호출 안 함
 *  - 깨진 JSON — 예외 전파 없음 (ERROR 로그만)
 */
@ExtendWith(MockitoExtension.class)
class OrderSseRedisSubscriberTest {

    @Mock RedisMessageListenerContainer container;
    @Mock OrderSseEmitterManager emitterManager;
    @Mock Message message;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private OrderSseRedisSubscriber subscriber;

    @BeforeEach
    void setUp() {
        subscriber = new OrderSseRedisSubscriber(container, emitterManager, objectMapper);
    }

    @Test
    @DisplayName("정상 envelope — deliverLocal 에 userId/eventName/payload 전달")
    void 정상_envelope_deliverLocal_호출() throws Exception {
        OrderSseEvent event = OrderSseEvent.submitted("1", "005930", "ODNO1");
        OrderSseEnvelope envelope = new OrderSseEnvelope(
                "order", objectMapper.valueToTree(event), 12345L);
        when(message.getChannel()).thenReturn(bytes("order:sse:user:42"));
        when(message.getBody()).thenReturn(bytes(objectMapper.writeValueAsString(envelope)));

        subscriber.onMessage(message, null);

        ArgumentCaptor<Long> userIdCap     = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> eventNameCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloadCap   = ArgumentCaptor.forClass(Object.class);
        verify(emitterManager).deliverLocal(userIdCap.capture(), eventNameCap.capture(), payloadCap.capture());

        assertThat(userIdCap.getValue()).isEqualTo(42L);
        assertThat(eventNameCap.getValue()).isEqualTo("order");
        // payload 는 JsonNode 로 전달 (envelope.payload() 그대로)
        assertThat(payloadCap.getValue()).isInstanceOf(JsonNode.class);
        JsonNode payloadNode = (JsonNode) payloadCap.getValue();
        assertThat(payloadNode.get("type").asText()).isEqualTo("ORDER_SUBMITTED");
        assertThat(payloadNode.get("orderId").asText()).isEqualTo("1");
    }

    @Test
    @DisplayName("비정상 채널 prefix — deliverLocal 호출 안 함")
    void 비정상_채널_prefix_무시() {
        when(message.getChannel()).thenReturn(bytes("market:tick:user:42"));

        subscriber.onMessage(message, null);

        verifyNoInteractions(emitterManager);
    }

    @Test
    @DisplayName("채널 suffix 숫자 아님 — deliverLocal 호출 안 함")
    void 채널_suffix_숫자아님_무시() {
        when(message.getChannel()).thenReturn(bytes("order:sse:user:abc"));

        subscriber.onMessage(message, null);

        verifyNoInteractions(emitterManager);
    }

    @Test
    @DisplayName("깨진 JSON — 예외 전파 없음")
    void 깨진_JSON_swallow() {
        when(message.getChannel()).thenReturn(bytes("order:sse:user:42"));
        when(message.getBody()).thenReturn(bytes("not-a-json"));

        assertThatCode(() -> subscriber.onMessage(message, null))
                .doesNotThrowAnyException();

        verifyNoInteractions(emitterManager);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
