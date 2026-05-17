package com.modu.backend.domain.trading.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 사용자별 SSE 연결 관리자
 *
 * userId 단위로 SseEmitter 한 개씩 보관한다.
 * 동일 사용자가 새로 connect 호출 시 기존 연결은 명시적으로 종료되고 새 연결로 대체된다.
 * (다중 탭 동시 지원이 필요해지면 별도 협의 — 본 클래스는 마지막 연결만 유지)
 *
 * [단일 인스턴스 한계]
 * ConcurrentHashMap 메모리 기반 — 다중 인스턴스로 확장 시 Redis Pub/Sub 도입 필요.
 *
 * [자동 정리]
 * SseEmitter 의 onCompletion / onTimeout / onError 콜백에서 자신을 Map 에서 제거한다.
 * 동일성 비교(remove(key, value)) 로 이미 교체된 새 emitter 는 보호한다.
 */
@Slf4j
@Component
public class OrderSseEmitterManager {

    /** 무기한 연결 유지 — 클라이언트 측 끊김으로 정리 */
    private static final long TIMEOUT_MS = Long.MAX_VALUE;

    /** 프론트와 약속된 단일 이벤트 name — data.type 으로 종류 구분 */
    private static final String EVENT_NAME = "order";

    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * 사용자 SSE 연결 등록
     * 동일 userId 의 기존 emitter 가 있으면 명시적으로 완료 처리 후 교체.
     *
     * @return 등록된 새 SseEmitter (컨트롤러가 그대로 반환)
     */
    public SseEmitter connect(Long userId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(e -> remove(userId, emitter));

        SseEmitter previous = emitters.put(userId, emitter);
        if (previous != null) {
            // 이전 emitter complete → onCompletion 콜백이 발동되지만, 동일성 체크로 새 emitter 는 보호됨
            previous.complete();
            log.info("SSE 기존 연결 교체 - userId: {}", userId);
        } else {
            log.info("SSE 연결 등록 - userId: {}", userId);
        }

        // 즉시 'connected' 이벤트 전송 — 응답 stream 시작을 트리거.
        // 첫 데이터가 없으면 nginx-ingress 가 응답을 빈 채로 인식해 keepalive timeout(60s) 에 끊김 → 499.
        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException e) {
            log.warn("SSE 초기 connected 이벤트 전송 실패 - userId: {}", userId, e);
            emitter.completeWithError(e);
            emitters.remove(userId, emitter);
        }

        return emitter;
    }

    /**
     * 사용자에게 이벤트 전송
     * 연결 없으면 조용히 무시 (사용자가 화면을 안 띄운 상태일 수 있음).
     * 전송 실패 시 emitter 완료 처리 + Map 제거.
     */
    public void send(Long userId, OrderSseEvent event) {
        SseEmitter emitter = emitters.get(userId);
        if (emitter == null) {
            log.info("SSE 연결 없음 - userId: {}, type: {}", userId, event.type());
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(EVENT_NAME).data(event));
        } catch (IOException e) {
            log.warn("SSE 전송 실패, 연결 제거 - userId: {}, type: {}", userId, event.type(), e);
            emitter.completeWithError(e);
            emitters.remove(userId, emitter);
        }
    }

    /**
     * 동일성 체크 후 제거 — 이미 다른 emitter 로 교체된 경우 새 emitter 보호
     */
    private void remove(Long userId, SseEmitter emitter) {
        emitters.remove(userId, emitter);
    }
}
