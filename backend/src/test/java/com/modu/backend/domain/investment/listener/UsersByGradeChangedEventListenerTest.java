package com.modu.backend.domain.investment.listener;

import com.modu.backend.domain.investment.event.UsersByGradeChangedEvent;
import com.modu.backend.domain.investment.repository.UsersByGradeRedisRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UsersByGradeChangedEventListenerTest {

    @Mock UsersByGradeRedisRepository repository;
    @InjectMocks UsersByGradeChangedEventListener listener;

    @Test
    @DisplayName("신규 profile (prev=null) — SADD 만 호출")
    void newProfile_callsAddOnly() {
        listener.handle(new UsersByGradeChangedEvent(1L, null, 3));

        verify(repository).addUser(1L, 3);
        verify(repository, never()).removeUser(anyLong(), anyInt());
    }

    @Test
    @DisplayName("등급 변경 — SREM prev + SADD new")
    void gradeChanged_callsRemoveAndAdd() {
        listener.handle(new UsersByGradeChangedEvent(1L, 2, 4));

        verify(repository).removeUser(1L, 2);
        verify(repository).addUser(1L, 4);
    }

    @Test
    @DisplayName("등급 동일 — Redis 호출 X (불필요 호출 방지)")
    void sameGrade_skips() {
        listener.handle(new UsersByGradeChangedEvent(1L, 3, 3));

        verify(repository, never()).removeUser(anyLong(), anyInt());
        verify(repository, never()).addUser(anyLong(), anyInt());
    }

    @Test
    @DisplayName("Repository 예외 시에도 throw X — 이벤트 처리 격리")
    void redisException_swallowed() {
        doThrow(new RuntimeException("redis down"))
                .when(repository).addUser(anyLong(), anyInt());

        listener.handle(new UsersByGradeChangedEvent(1L, null, 3));
        // 통과하면 성공
    }
}
