package com.modu.backend.domain.trading.sse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OrderSseEmitterManager 단위 테스트
 *
 * SseEmitter 의 내부 IO 는 servlet 컨테이너 의존성이라 직접 검증이 어렵다.
 * 본 테스트는 Manager 의 자체 제어 흐름(등록/교체/조용한 무시)에 집중한다.
 * 실제 SSE 송수신은 통합 테스트 영역.
 */
class OrderSseEmitterManagerTest {

    private OrderSseEmitterManager manager;

    @BeforeEach
    void setUp() {
        manager = new OrderSseEmitterManager();
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
    @DisplayName("연결 없는 userId 에게 send 시 예외 없이 무시")
    void 연결_없는_userId_send_무시() {
        // 등록 안 된 상태에서 send — 예외 발생하지 않아야 함
        manager.send(999L, OrderSseEvent.submitted("1", "005930", "ODNO1"));
    }

    @Test
    @DisplayName("send — 등록된 emitter 에 이벤트 호출 (예외 없음)")
    void send_등록된_emitter_에_전달() {
        manager.connect(1L);

        // 실제 IO 가 일어나지 않는 환경에서도 emitter.send 가 ResponseBodyEmitter 의 큐에 적재되며
        // IOException 없이 정상 완료되어야 한다 (만약 발생하면 manager 가 자체 정리)
        manager.send(1L, OrderSseEvent.submitted("1", "005930", "ODNO1"));
    }

    @SuppressWarnings("unchecked")
    private Map<Long, SseEmitter> currentEmitters() {
        return (Map<Long, SseEmitter>) ReflectionTestUtils.getField(manager, "emitters");
    }
}
