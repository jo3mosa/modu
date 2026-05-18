package com.modu.backend.domain.trading.execution.debug;

import com.modu.backend.domain.trading.execution.client.KisExecutionWebSocketClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// S14P31B106-291 수동 검증용 임시 endpoint — 검증 완료 후 제거 예정
@Slf4j
@RestController
@RequestMapping("/debug/exec")
@RequiredArgsConstructor
public class ExecutionSubscribeDebugController {

    private final KisExecutionWebSocketClient client;

    @GetMapping("/subscribe")
    public Map<String, Object> subscribe(@RequestParam Long userId) {
        log.warn("[DEBUG] ExecutionWS subscribe 호출 - userId: {}", userId);
        client.subscribe(userId);
        return Map.of("status", "ok", "userId", userId);
    }

    @GetMapping("/unsubscribe")
    public Map<String, Object> unsubscribe(@RequestParam Long userId) {
        log.warn("[DEBUG] ExecutionWS unsubscribe 호출 - userId: {}", userId);
        client.unsubscribe(userId);
        return Map.of("status", "ok", "userId", userId);
    }
}
