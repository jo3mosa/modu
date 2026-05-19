package com.modu.backend.domain.investment.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsersByGradeRedisRepositoryTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private SetOperations<String, String> setOperations;

    @InjectMocks
    private UsersByGradeRedisRepository repository;

    @Test
    @DisplayName("addUser - 정상 grade 는 SADD 호출")
    void addUser_validGrade_callsSadd() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);

        repository.addUser(1001L, 3);

        verify(setOperations).add("users:by_grade:3", "1001");
    }

    @Test
    @DisplayName("addUser - grade 0 은 early return")
    void addUser_gradeZero_earlyReturn() {
        repository.addUser(1001L, 0);

        verify(redisTemplate, never()).opsForSet();
    }

    @Test
    @DisplayName("addUser - grade 6 은 early return")
    void addUser_gradeSix_earlyReturn() {
        repository.addUser(1001L, 6);

        verify(redisTemplate, never()).opsForSet();
    }

    @Test
    @DisplayName("addUser - Redis 예외 시에도 throw 하지 않음")
    void addUser_redisException_swallowed() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        doThrow(new RuntimeException("redis down")).when(setOperations).add(anyString(), anyString());

        repository.addUser(1001L, 3);
    }

    @Test
    @DisplayName("removeUser - 정상 grade 는 SREM 호출")
    void removeUser_validGrade_callsSrem() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);

        repository.removeUser(1001L, 3);

        verify(setOperations).remove("users:by_grade:3", "1001");
    }

    @Test
    @DisplayName("removeUser - grade 범위 밖은 early return")
    void removeUser_invalidGrade_earlyReturn() {
        repository.removeUser(1001L, 0);
        repository.removeUser(1001L, 6);

        verify(redisTemplate, never()).opsForSet();
    }

    @Test
    @DisplayName("removeUser - Redis 예외 시에도 throw 하지 않음")
    void removeUser_redisException_swallowed() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        doThrow(new RuntimeException("redis down")).when(setOperations).remove(anyString(), any(Object[].class));

        repository.removeUser(1001L, 3);
    }

    @Test
    @DisplayName("addUsersBatch - 빈 입력은 pipeline 호출 안 함")
    void addUsersBatch_empty_skipsPipeline() {
        repository.addUsersBatch(Map.of());
        repository.addUsersBatch(null);

        verify(redisTemplate, never()).executePipelined(any(RedisCallback.class));
    }

    @Test
    @DisplayName("addUsersBatch - 정상 입력은 pipeline 호출")
    void addUsersBatch_validInput_callsPipeline() {
        repository.addUsersBatch(Map.of(
                3, List.of(1001L, 1002L),
                5, List.of(2001L)
        ));

        verify(redisTemplate, times(1)).executePipelined(any(RedisCallback.class));
    }

    @Test
    @DisplayName("addUsersBatch - Redis 예외 시에도 throw 하지 않음")
    void addUsersBatch_redisException_swallowed() {
        when(redisTemplate.executePipelined(any(RedisCallback.class)))
                .thenThrow(new RuntimeException("redis down"));

        repository.addUsersBatch(Map.of(3, List.of(1001L)));
    }

    @Test
    @DisplayName("getUsers - 정상 grade 는 SMEMBERS 호출")
    void getUsers_validGrade_returnsMembers() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members("users:by_grade:3")).thenReturn(Set.of("1001", "1002"));

        Set<String> result = repository.getUsers(3);

        assertThat(result).containsExactlyInAnyOrder("1001", "1002");
    }

    @Test
    @DisplayName("getUsers - grade 범위 밖은 빈 Set 반환")
    void getUsers_invalidGrade_returnsEmpty() {
        Set<String> result = repository.getUsers(0);

        assertThat(result).isEmpty();
        verify(redisTemplate, never()).opsForSet();
    }

    @Test
    @DisplayName("getUsers - members 가 null 이면 빈 Set 반환")
    void getUsers_nullMembers_returnsEmpty() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(anyString())).thenReturn(null);

        assertThat(repository.getUsers(3)).isEmpty();
    }

    @Test
    @DisplayName("getUsers - Redis 예외 시 빈 Set 반환")
    void getUsers_redisException_returnsEmpty() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(anyString())).thenThrow(new RuntimeException("redis down"));

        assertThat(repository.getUsers(3)).isEmpty();
    }
}
