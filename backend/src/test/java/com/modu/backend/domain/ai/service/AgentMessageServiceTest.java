package com.modu.backend.domain.ai.service;

import com.modu.backend.domain.ai.dto.AgentMessagePageResponse;
import com.modu.backend.domain.ai.entity.AgentMessage;
import com.modu.backend.domain.ai.entity.AgentType;
import com.modu.backend.domain.ai.event.AgentMessageSavedEvent;
import com.modu.backend.domain.ai.repository.AgentMessageRepository;
import com.modu.backend.global.error.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentMessageServiceTest {

    private static final Long USER_ID = 1L;
    private static final String STOCK = "005930";

    @Mock
    AgentMessageRepository repository;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @InjectMocks
    AgentMessageService service;

    // ─────────────────────────────────────────────────────────────────
    // save
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("save 성공 시 INSERT 후 AgentMessageSavedEvent 를 발행한다")
    void save_publishesEvent() {
        // given
        AgentMessageService.SaveCommand cmd = command(100L, AgentType.BULL, 0, "강세 시그널");
        AgentMessage saved = message(999L, 100L, AgentType.BULL, 0, "강세 시그널");

        when(repository.existsByUserIdAndJudgmentIdAndAgentAndSeq(USER_ID, 100L, AgentType.BULL, 0))
                .thenReturn(false);
        when(repository.save(any(AgentMessage.class))).thenReturn(saved);

        // when
        AgentMessage result = service.save(cmd);

        // then
        assertThat(result).isSameAs(saved);
        ArgumentCaptor<AgentMessageSavedEvent> captor = ArgumentCaptor.forClass(AgentMessageSavedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().message()).isSameAs(saved);
    }

    @Test
    @DisplayName("동일 (judgmentId, agent, seq) 가 이미 존재하면 INSERT/이벤트 발행 모두 skip")
    void save_idempotentSkip() {
        // given
        AgentMessageService.SaveCommand cmd = command(100L, AgentType.BULL, 0, "강세 시그널");
        when(repository.existsByUserIdAndJudgmentIdAndAgentAndSeq(USER_ID, 100L, AgentType.BULL, 0))
                .thenReturn(true);

        // when
        AgentMessage result = service.save(cmd);

        // then
        assertThat(result).isNull();
        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("judgmentId 가 null 인 자유 발화는 멱등 검사를 건너뛰고 항상 INSERT")
    void save_freeUtteranceSkipsIdempotencyCheck() {
        // given
        AgentMessageService.SaveCommand cmd = command(null, AgentType.STRATEGY, 0, "자유 발화");
        AgentMessage saved = message(1L, null, AgentType.STRATEGY, 0, "자유 발화");
        when(repository.save(any(AgentMessage.class))).thenReturn(saved);

        // when
        service.save(cmd);

        // then
        verify(repository, never()).existsByUserIdAndJudgmentIdAndAgentAndSeq(any(), any(), any(), eq(0));
        verify(repository).save(any(AgentMessage.class));
    }

    @Test
    @DisplayName("필수 필드 누락 시 ValidationException")
    void save_validates() {
        AgentMessageService.SaveCommand cmd = new AgentMessageService.SaveCommand(
                USER_ID, "", null, AgentType.BULL, 0, "text", OffsetDateTime.now());

        assertThatThrownBy(() -> service.save(cmd))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("stockCode");
    }

    // ─────────────────────────────────────────────────────────────────
    // getMessages — cursor pagination
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("before 미전달 시 findRecent 호출, size+1 페이지로 hasMore 판정")
    void getMessages_firstPage_hasMore() {
        // given — size=3 요청 → repository 는 4개 반환 (hasMore true)
        int size = 3;
        List<AgentMessage> rows = IntStream.rangeClosed(1, 4)
                .mapToObj(i -> message((long) i, 100L, AgentType.BULL, i,
                        "msg" + i, OffsetDateTime.parse("2026-05-18T10:0" + (5 - i) + ":00+09:00")))
                .toList();
        when(repository.findRecent(eq(USER_ID), eq(STOCK), eq(PageRequest.of(0, size + 1))))
                .thenReturn(rows);

        // when
        AgentMessagePageResponse response = service.getMessages(USER_ID, STOCK, null, size);

        // then
        assertThat(response.content()).hasSize(size);
        assertThat(response.hasMore()).isTrue();
        // nextCursor 는 trimmed 마지막(3번째) 항목 createdAt
        assertThat(response.nextCursor()).isEqualTo(rows.get(2).getCreatedAt());
    }

    @Test
    @DisplayName("결과가 size 이하면 hasMore=false, nextCursor=null")
    void getMessages_lastPage() {
        int size = 5;
        List<AgentMessage> rows = List.of(message(1L, 100L, AgentType.BULL, 0, "only"));
        when(repository.findRecent(eq(USER_ID), eq(STOCK), eq(PageRequest.of(0, size + 1))))
                .thenReturn(rows);

        AgentMessagePageResponse response = service.getMessages(USER_ID, STOCK, null, size);

        assertThat(response.content()).hasSize(1);
        assertThat(response.hasMore()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    @DisplayName("before 전달 시 findBefore 호출")
    void getMessages_withCursor() {
        OffsetDateTime cursor = OffsetDateTime.parse("2026-05-18T10:00:00+09:00");
        when(repository.findBefore(eq(USER_ID), eq(STOCK), eq(cursor), any()))
                .thenReturn(List.of());

        service.getMessages(USER_ID, STOCK, cursor, 10);

        verify(repository).findBefore(eq(USER_ID), eq(STOCK), eq(cursor), eq(PageRequest.of(0, 11)));
    }

    @Test
    @DisplayName("stockCode 누락 시 ValidationException")
    void getMessages_stockCodeRequired() {
        assertThatThrownBy(() -> service.getMessages(USER_ID, " ", null, 10))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("stockCode");
    }

    @Test
    @DisplayName("size 미전달 시 기본 50, 100 초과면 100 으로 clamp")
    void getMessages_clampSize() {
        when(repository.findRecent(eq(USER_ID), eq(STOCK), any())).thenReturn(List.of());

        // null → 기본 50 → size+1 = 51
        service.getMessages(USER_ID, STOCK, null, null);
        verify(repository).findRecent(eq(USER_ID), eq(STOCK), eq(PageRequest.of(0, 51)));

        // 999 → 100 으로 clamp → size+1 = 101
        service.getMessages(USER_ID, STOCK, null, 999);
        verify(repository).findRecent(eq(USER_ID), eq(STOCK), eq(PageRequest.of(0, 101)));
    }

    // ─────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────

    private AgentMessageService.SaveCommand command(Long judgmentId, AgentType agent, int seq, String text) {
        return new AgentMessageService.SaveCommand(
                USER_ID, STOCK, judgmentId, agent, seq, text,
                OffsetDateTime.parse("2026-05-18T10:00:00+09:00"));
    }

    private AgentMessage message(Long id, Long judgmentId, AgentType agent, int seq, String text) {
        return message(id, judgmentId, agent, seq, text,
                OffsetDateTime.parse("2026-05-18T10:00:00+09:00"));
    }

    private AgentMessage message(Long id, Long judgmentId, AgentType agent, int seq,
                                 String text, OffsetDateTime createdAt) {
        AgentMessage m = AgentMessage.builder()
                .userId(USER_ID)
                .stockCode(STOCK)
                .judgmentId(judgmentId)
                .agent(agent)
                .seq(seq)
                .text(text)
                .createdAt(createdAt)
                .build();
        ReflectionTestUtils.setField(m, "id", id);
        return m;
    }
}
