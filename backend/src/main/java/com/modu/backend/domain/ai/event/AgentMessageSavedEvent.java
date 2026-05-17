package com.modu.backend.domain.ai.event;

import com.modu.backend.domain.ai.entity.AgentMessage;

/**
 * AgentMessage INSERT 트랜잭션이 커밋된 이후 발화되는 도메인 이벤트
 *
 * AgentMessageService.save() 가 ApplicationEventPublisher 로 publish 하고,
 * @TransactionalEventListener(AFTER_COMMIT) 리스너가 SSE 브로드캐스트를 수행한다.
 *
 * [왜 분리하는가]
 * DB 저장과 SSE 푸시를 한 트랜잭션 안에 묶으면, 커밋 실패 시에도 푸시가 나가
 * 프론트와 DB 상태가 어긋난다. AFTER_COMMIT 패턴으로 "저장 성공 → 푸시" 순서를 보장.
 */
public record AgentMessageSavedEvent(AgentMessage message) {
}
