package com.modu.backend.domain.trading.sse.redis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modu.backend.domain.trading.sse.OrderSseEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * OrderSseRedisPublisher 단위 테스트
 *
 * 검증 범위:
 *  - 정상 publish 시 채널명/envelope JSON 가 기대대로 전송
 *  - Redis 장애 시 호출자에게 예외 전파 안 됨 (fire-and-forget)
 */
@ExtendWith(MockitoExtension.class)
class OrderSseRedisPublisherTest {

    @Mock StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private OrderSseRedisPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OrderSseRedisPublisher(redisTemplate, objectMapper);
    }

    @Test
    @DisplayName("정상 — 채널 = userChannel, envelope.eventName/payload 일치")
    void 정상_publish_envelope_검증() throws Exception {
        OrderSseEvent event = OrderSseEvent.submitted("1", "005930", "ODNO1");

        publisher.publish(42L, "order", event);

        ArgumentCaptor<String> channelCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bodyCap    = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(channelCap.capture(), bodyCap.capture());

        assertThat(channelCap.getValue()).isEqualTo("order:sse:user:42");

        JsonNode envelopeJson = objectMapper.readTree(bodyCap.getValue());
        assertThat(envelopeJson.get("eventName").asText()).isEqualTo("order");
        assertThat(envelopeJson.get("ts").asLong()).isGreaterThan(0);
        assertThat(envelopeJson.get("payload").get("type").asText()).isEqualTo("ORDER_SUBMITTED");
        assertThat(envelopeJson.get("payload").get("orderId").asText()).isEqualTo("1");
        assertThat(envelopeJson.get("payload").get("stockCode").asText()).isEqualTo("005930");
        assertThat(envelopeJson.get("payload").get("kisOrderNo").asText()).isEqualTo("ODNO1");
    }

    @Test
    @DisplayName("Redis 장애 — RedisConnectionFailureException 흡수, 호출자에게 전파 X")
    void publish_실패_swallow() {
        doThrow(new RedisConnectionFailureException("redis down"))
                .when(redisTemplate).convertAndSend(anyString(), anyString());

        assertThatCode(() ->
                publisher.publish(42L, "order",
                        OrderSseEvent.submitted("1", "005930", "ODNO1"))
        ).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("임의 eventName/payload — envelope eventName 그대로 실림")
    void 임의_eventName_payload_위임() throws Exception {
        record AgentMsg(String text) {}
        AgentMsg payload = new AgentMsg("hi");

        publisher.publish(7L, "agent-message", payload);

        ArgumentCaptor<String> channelCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bodyCap    = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(channelCap.capture(), bodyCap.capture());

        assertThat(channelCap.getValue()).isEqualTo("order:sse:user:7");
        JsonNode envelopeJson = objectMapper.readTree(bodyCap.getValue());
        assertThat(envelopeJson.get("eventName").asText()).isEqualTo("agent-message");
        assertThat(envelopeJson.get("payload").get("text").asText()).isEqualTo("hi");
    }

    @Test
    @DisplayName("eq 채널키 — userId 변경 시 채널키 변경 확인")
    void 사용자별_채널_분리() {
        publisher.publish(1L, "order", OrderSseEvent.submitted("1", "005930", "ODNO1"));
        publisher.publish(2L, "order", OrderSseEvent.submitted("2", "005930", "ODNO2"));

        verify(redisTemplate).convertAndSend(eq("order:sse:user:1"), anyString());
        verify(redisTemplate).convertAndSend(eq("order:sse:user:2"), anyString());
    }
}
