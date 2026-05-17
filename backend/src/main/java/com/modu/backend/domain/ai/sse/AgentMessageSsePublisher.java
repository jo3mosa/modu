package com.modu.backend.domain.ai.sse;

import com.modu.backend.domain.ai.entity.AgentMessage;
import com.modu.backend.domain.ai.event.AgentMessageSavedEvent;
import com.modu.backend.domain.trading.sse.OrderSseEmitterManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * AgentMessage 저장 트랜잭션이 커밋된 직후 SSE 로 브로드캐스트
 *
 * [왜 AFTER_COMMIT 인가]
 * DB 저장 트랜잭션이 롤백되어도 SSE 가 나가버리면 프론트와 DB 상태가 어긋난다.
 * AFTER_COMMIT 단계는 commit 이 성공한 직후 동작 → "저장 성공 → 푸시" 순서 보장.
 *
 * [왜 별도 빈인가]
 * AgentMessageService 안에 @TransactionalEventListener 를 두면 self-invocation
 * (Spring AOP 프록시 우회) 위험. 리스너는 항상 별도 빈으로 분리.
 *
 * [예외 정책]
 * SSE 전송 실패는 비즈니스 흐름에 영향 주지 않아야 함 → 로그만 남기고 흡수.
 * 연결 없음 / IO 실패는 OrderSseEmitterManager 가 내부적으로 처리.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentMessageSsePublisher {

    private final OrderSseEmitterManager sseEmitterManager;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAgentMessageSaved(AgentMessageSavedEvent event) {
        AgentMessage message = event.message();
        try {
            sseEmitterManager.send(
                    message.getUserId(),
                    AgentMessageSseEvent.EVENT_NAME,
                    AgentMessageSseEvent.from(message)
            );
        } catch (Exception e) {
            log.warn("AgentMessage SSE 푸시 실패 - userId: {}, messageId: {}",
                    message.getUserId(), message.getId(), e);
        }
    }
}
