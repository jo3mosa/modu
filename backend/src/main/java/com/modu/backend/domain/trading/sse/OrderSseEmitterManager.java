package com.modu.backend.domain.trading.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class OrderSseEmitterManager {

    // 동시 접속 처리를 위해 ConcurrentHashMap 사용
    // userId → SseEmitter 매핑
    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * SSE 연결 수립
     * 프론트엔드가 /api/v1/orders/connect 호출 시 실행
     * Long.MAX_VALUE: 연결을 끊지 않고 계속 유지
     */
    public SseEmitter connect(Long userId) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.put(userId, emitter);

        // 연결 종료/타임아웃/에러 시 Map에서 제거 (메모리 누수 방지)
        emitter.onCompletion(() -> emitters.remove(userId));
        emitter.onTimeout(() -> emitters.remove(userId));
        emitter.onError(e -> emitters.remove(userId));

        log.info("SSE 연결 수립 - userId={}", userId);
        return emitter;
    }

    /**
     * 특정 유저에게 이벤트 전송
     * SSE 연결이 없으면 (앱 미접속 등) 무시
     */
    public void send(Long userId, Object data) {
        SseEmitter emitter = emitters.get(userId);
        if (emitter == null) return; // 연결 없으면 무시

        try {
            emitter.send(SseEmitter.event()
                    .name("order")  // 프론트에서 addEventListener("order", ...) 로 수신
                    .data(data));
        } catch (IOException e) {
            // 전송 실패 = 연결 끊김 → Map에서 제거
            log.warn("SSE 전송 실패 - userId={}", userId);
            emitters.remove(userId);
        }
    }
}
