package com.modu.backend.domain.trading.sse;

import com.modu.backend.domain.trading.sse.redis.OrderSseRedisPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * OrderSseEmitterManager 단위 테스트
 *
 * SseEmitter 의 내부 IO 는 servlet 컨테이너 의존성이라 직접 검증이 어렵다.
 * 본 테스트는 다음에 집중:
 *  - 자체 제어 흐름(등록/교체)
 *  - send 가 local emitter 가 아니라 {@link OrderSseRedisPublisher} 로만 위임됨 (S14P31B106-351)
 *  - deliverLocal 이 등록된 emitter 에 전달 / 미등록 시 조용히 무시
 * 실제 SSE 송수신은 통합 테스트 영역.
 */
@ExtendWith(MockitoExtension.class)
class OrderSseEmitterManagerTest {

    @Mock OrderSseRedisPublisher redisPublisher;

    private OrderSseEmitterManager manager;

    @BeforeEach
    void setUp() {
        manager = new OrderSseEmitterManager(redisPublisher);
    }

    @Test
    @DisplayName("connect — SseEmitter 반환 + 내부 맵 등록")
    void connect_emitter_등록() {
        SseEmitter emitter = manager.connect(1L);

        assertThat(emitter).isNotNull();
        assertThat(currentEmitters()).containsEntry(1L, emitter);
    }

    @Test
    @DisplayName("동일 userId 두 번 connect — 새 emitter 가 맵을 차지")
    void 동일_userId_재연결_새_emitter_차지() {
        SseEmitter first  = manager.connect(1L);
        SseEmitter second = manager.connect(1L);

        assertThat(second).isNotSameAs(first);
        assertThat(currentEmitters().get(1L)).isSameAs(second);
    }

    @Test
    @DisplayName("send(OrderSseEvent) — Redis publisher 로 위임 (local emitter 무시)")
    void send_OrderSseEvent_redis_위임() {
        OrderSseEvent event = OrderSseEvent.submitted("1", "005930", "ODNO1");

        manager.send(1L, event);

        verify(redisPublisher).publish(eq(1L), eq("order"), eq(event));
    }

    @Test
    @DisplayName("send(eventName, payload) — Redis publisher 로 위임")
    void send_genericPayload_redis_위임() {
        Object payload = new Object();

        manager.send(7L, "agent-message", payload);

        verify(redisPublisher).publish(eq(7L), eq("agent-message"), eq(payload));
    }

    @Test
    @DisplayName("연결 없는 userId 에게 send — publish 는 호출되지만 예외 없음")
    void 연결_없는_userId_send_publish_만_호출() {
        // 등록 안 된 상태에서 send — 매니저는 publish 만 하므로 emitter 부재 무관
        manager.send(999L, OrderSseEvent.submitted("1", "005930", "ODNO1"));

        verify(redisPublisher).publish(eq(999L), eq("order"), any());
    }

    @Test
    @DisplayName("deliverLocal — 등록된 emitter 에 호출 (예외 없이 완료)")
    void deliverLocal_등록된_emitter_에_전달() {
        manager.connect(1L);

        // 실제 IO 가 일어나지 않는 환경에서도 emitter.send 가 ResponseBodyEmitter 의 큐에 적재되며
        // IOException 없이 정상 완료되어야 한다 (만약 발생하면 manager 가 자체 정리)
        manager.deliverLocal(1L, "order", OrderSseEvent.submitted("1", "005930", "ODNO1"));

        // Redis publisher 는 호출되지 않아야 한다 (deliverLocal 은 subscriber 전용)
        verifyNoInteractions(redisPublisher);
    }

    @Test
    @DisplayName("deliverLocal — 연결 없는 userId 면 조용히 무시 (예외 없음)")
    void deliverLocal_연결_없으면_무시() {
        manager.deliverLocal(999L, "order", OrderSseEvent.submitted("1", "005930", "ODNO1"));

        verifyNoInteractions(redisPublisher);
    }

    @SuppressWarnings("unchecked")
    private Map<Long, SseEmitter> currentEmitters() {
        return (Map<Long, SseEmitter>) ReflectionTestUtils.getField(manager, "emitters");
    }
}
