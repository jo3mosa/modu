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
    @Transactional
    public AgentMessage save(SaveCommand cmd) {
        cmd.validate();

        if (cmd.judgmentId() != null && agentMessageRepository
                .existsByUserIdAndJudgmentIdAndAgentAndSeq(
                        cmd.userId(), cmd.judgmentId(), cmd.agent(), cmd.seq())) {
            log.debug("AgentMessage 중복 skip - userId: {}, judgmentId: {}, agent: {}, seq: {}",
                    cmd.userId(), cmd.judgmentId(), cmd.agent(), cmd.seq());
            return null;
        }

        AgentMessage saved = agentMessageRepository.save(AgentMessage.builder()
                .userId(cmd.userId())
                .stockCode(cmd.stockCode())
                .judgmentId(cmd.judgmentId())
                .agent(cmd.agent())
                .seq(cmd.seq())
                .text(cmd.text())
                .createdAt(cmd.createdAt())
                .build());

        eventPublisher.publishEvent(new AgentMessageSavedEvent(saved));
        return saved;
    }

    /**
     * 채널별 메시지 조회 (커서 페이지네이션)
     *
     * @param before null 이면 최신부터 size 개. 값이 있으면 그 시각보다 이전 size 개.
     * @return DESC 정렬 + nextCursor (마지막 항목 createdAt) + hasMore
     */
    @Transactional(readOnly = true)
    public AgentMessagePageResponse getMessages(
            Long userId, String stockCode, OffsetDateTime before, Integer size) {
        if (stockCode == null || stockCode.isBlank()) {
            throw new ValidationException("stockCode 는 필수입니다.");
        }
        int pageSize = clampSize(size);
        // 마지막 페이지인지 판별하려면 size + 1 개 요청해서 N+1 번째 존재 여부로 hasMore 결정
        PageRequest pageable = PageRequest.of(0, pageSize + 1);

        List<AgentMessage> rows = (before == null)
                ? agentMessageRepository.findRecent(userId, stockCode, pageable)
                : agentMessageRepository.findBefore(userId, stockCode, before, pageable);

        boolean hasMore = rows.size() > pageSize;
        List<AgentMessage> trimmed = hasMore ? rows.subList(0, pageSize) : rows;
        OffsetDateTime nextCursor = hasMore && !trimmed.isEmpty()
                ? trimmed.get(trimmed.size() - 1).getCreatedAt()
                : null;

        List<AgentMessageResponse> content = trimmed.stream()
                .map(AgentMessageResponse::from)
                .toList();

        return new AgentMessagePageResponse(content, nextCursor, hasMore);
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
