package com.modu.backend.domain.ai.service;

import com.modu.backend.domain.ai.dto.AgentMessagePageResponse;
import com.modu.backend.domain.ai.dto.AgentMessageResponse;
import com.modu.backend.domain.ai.entity.AgentMessage;
import com.modu.backend.domain.ai.entity.AgentType;
import com.modu.backend.domain.ai.event.AgentMessageSavedEvent;
import com.modu.backend.domain.ai.repository.AgentMessageRepository;
import com.modu.backend.global.error.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 에이전트 메시지 저장/조회 서비스
 *
 * [쓰기 흐름]
 *  AgentMessageConsumer.onMessage()
 *   → save(SaveCommand) — INSERT
 *   → ApplicationEventPublisher.publishEvent(AgentMessageSavedEvent)
 *   → AgentMessageSsePublisher (AFTER_COMMIT) — SSE 브로드캐스트
 *
 * [읽기 흐름]
 *  REST GET /api/v1/ai-agent/messages
 *   → getMessages(userId, stockCode, before, size) — 커서 페이지네이션
 *
 * [멱등성]
 * (user_id, judgment_id, agent, seq) 중복은 조기 반환. 추가로 DB partial unique index 가
 * 동시성 race 까지 방어 (예외 catch 후 정상 흐름으로 흡수).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentMessageService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final AgentMessageRepository agentMessageRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 에이전트 발화 저장 + AFTER_COMMIT SSE 푸시 이벤트 발행
     *
     * 멱등: 동일 (user_id, judgment_id, agent, seq) 가 이미 존재하면 조용히 skip.
     * judgment_id 가 null 인 자유 발화는 멱등 검사 대상 외.
     *
     * @return INSERT 된 entity (skip 된 경우 null)
     */
    /**
     * @return INSERT 된 entity, 멱등 중복으로 skip 된 경우 null
     *
     * [멱등 처리 — 이중 방어]
     *  1. exists 선조회로 정상 흐름 차단 (대다수 케이스)
     *  2. 동시성 race 로 unique index 충돌 발생 시 DataIntegrityViolationException 흡수
     *     → 동시 두 컨슈머 스레드가 같은 (user_id, judgment_id, agent, seq) 를 동시에 처리해도
     *       한쪽은 정상 INSERT, 다른 쪽은 조용히 skip 으로 처리되어 Kafka 무한 재시도 방지.
     *
     * [@Transactional 위치]
     * DataIntegrityViolationException 을 잡으려면 트랜잭션이 commit/rollback 되는 시점에 잡혀야 한다.
     * 본 메서드의 트랜잭션 경계 안에서 saveAndFlush 로 즉시 INSERT 를 시도하고, 충돌 시 위로 던지지
     * 않고 흡수한다. 흡수 시 트랜잭션은 rollback 되지만 호출자(Consumer) 는 ack 를 진행한다.
     */
    @Transactional
    public AgentMessage save(SaveCommand cmd) {
        cmd.validate();

        if (cmd.judgmentId() != null && agentMessageRepository
                .existsByUserIdAndJudgmentIdAndAgentAndSeq(
                        cmd.userId(), cmd.judgmentId(), cmd.agent(), cmd.seq())) {
            log.debug("AgentMessage 중복 skip(선조회) - userId: {}, judgmentId: {}, agent: {}, seq: {}",
                    cmd.userId(), cmd.judgmentId(), cmd.agent(), cmd.seq());
            return null;
        }

        AgentMessage entity = AgentMessage.builder()
                .userId(cmd.userId())
                .stockCode(cmd.stockCode())
                .judgmentId(cmd.judgmentId())
                .agent(cmd.agent())
                .seq(cmd.seq())
                .text(cmd.text())
                .createdAt(cmd.createdAt())
                .build();

        AgentMessage saved;
        try {
            saved = agentMessageRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            // 동시성 race — 다른 스레드가 먼저 같은 (user_id, judgment_id, agent, seq) 를 INSERT
            log.debug("AgentMessage 중복 skip(unique 충돌) - userId: {}, judgmentId: {}, agent: {}, seq: {}",
                    cmd.userId(), cmd.judgmentId(), cmd.agent(), cmd.seq());
            return null;
        }

        eventPublisher.publishEvent(new AgentMessageSavedEvent(saved));
        return saved;
    }

    /**
     * 채널별 메시지 조회 (복합 커서 페이지네이션)
     *
     * [복합 커서 (createdAt, id)]
     * 같은 createdAt 을 가진 메시지가 페이지 경계에서 누락/중복되지 않도록 id 를 tie-breaker 로 묶음.
     * 첫 페이지: before == null && beforeId == null → findRecent.
     * 후속 페이지: 둘 다 not null → findBefore (둘 중 하나만 전달 시 ValidationException).
     *
     * @return content DESC + nextCursor/nextCursorId + hasMore
     */
    @Transactional(readOnly = true)
    public AgentMessagePageResponse getMessages(
            Long userId, String stockCode, OffsetDateTime before, Long beforeId, Integer size) {
        if (stockCode == null || stockCode.isBlank()) {
            throw new ValidationException("stockCode 는 필수입니다.");
        }
        if ((before == null) != (beforeId == null)) {
            throw new ValidationException("before 와 beforeId 는 함께 전달해야 합니다.");
        }
        int pageSize = clampSize(size);
        // 마지막 페이지인지 판별하려면 size + 1 개 요청해서 N+1 번째 존재 여부로 hasMore 결정
        PageRequest pageable = PageRequest.of(0, pageSize + 1);

        List<AgentMessage> rows = (before == null)
                ? agentMessageRepository.findRecent(userId, stockCode, pageable)
                : agentMessageRepository.findBefore(userId, stockCode, before, beforeId, pageable);

        boolean hasMore = rows.size() > pageSize;
        List<AgentMessage> trimmed = hasMore ? rows.subList(0, pageSize) : rows;

        OffsetDateTime nextCursor = null;
        Long nextCursorId = null;
        if (hasMore && !trimmed.isEmpty()) {
            AgentMessage last = trimmed.get(trimmed.size() - 1);
            nextCursor = last.getCreatedAt();
            nextCursorId = last.getId();
        }

        List<AgentMessageResponse> content = trimmed.stream()
                .map(AgentMessageResponse::from)
                .toList();

        return new AgentMessagePageResponse(content, nextCursor, nextCursorId, hasMore);
    }

    private int clampSize(Integer size) {
        if (size == null || size <= 0) return DEFAULT_PAGE_SIZE;
        return Math.min(size, MAX_PAGE_SIZE);
    }

    /**
     * 저장 명령 — Kafka Consumer / 내부 호출자가 사용.
     * agent 는 enum 타입으로 받음 (Kafka 단계에서 String → enum 변환 책임 분리).
     */
    public record SaveCommand(
            Long userId,
            String stockCode,
            Long judgmentId,
            AgentType agent,
            int seq,
            String text,
            OffsetDateTime createdAt
    ) {
        void validate() {
            if (userId == null)                   throw new ValidationException("userId 는 필수입니다.");
            if (stockCode == null || stockCode.isBlank())
                                                  throw new ValidationException("stockCode 는 필수입니다.");
            if (agent == null)                    throw new ValidationException("agent 는 필수입니다.");
            if (text == null || text.isBlank())   throw new ValidationException("text 는 필수입니다.");
            if (seq < 0)                          throw new ValidationException("seq 는 0 이상이어야 합니다.");
        }
    }
}
