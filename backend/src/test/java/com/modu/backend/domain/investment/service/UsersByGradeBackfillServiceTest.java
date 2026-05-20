package com.modu.backend.domain.investment.service;

import com.modu.backend.domain.investment.entity.InvestmentProfile;
import com.modu.backend.domain.investment.repository.InvestmentProfileRepository;
import com.modu.backend.domain.investment.repository.UsersByGradeRedisRepository;
import com.modu.backend.domain.investment.service.UsersByGradeBackfillService.BackfillResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsersByGradeBackfillServiceTest {

    @Mock InvestmentProfileRepository investmentProfileRepository;
    @Mock UsersByGradeRedisRepository usersByGradeRedisRepository;
    @InjectMocks UsersByGradeBackfillService service;

    @Test
    @DisplayName("backfillAll - 등급별 그룹화 후 addUsersBatch 호출 + success")
    void backfillAll_success() {
        givenSinglePage(List.of(
                profile(1L, "STABLE"),         // 1
                profile(2L, "ACTIVE"),         // 4
                profile(3L, "ACTIVE"),         // 4
                profile(4L, "AGGRESSIVE")      // 5
        ));

        BackfillResult result = service.backfillAll();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Integer, ? extends Collection<Long>>> captor =
                ArgumentCaptor.forClass(Map.class);
        verify(usersByGradeRedisRepository).addUsersBatch(captor.capture());
        Map<Integer, ? extends Collection<Long>> grouped = captor.getValue();
        assertThat(grouped.get(1)).containsExactly(1L);
        assertThat(grouped.get(4)).containsExactlyInAnyOrder(2L, 3L);
        assertThat(grouped.get(5)).containsExactly(4L);
        assertThat(result.success()).isTrue();
        assertThat(result.count()).isEqualTo(4);
    }

    @Test
    @DisplayName("backfillAll - profile 0건이면 addUsersBatch 호출 X + empty result")
    void backfillAll_empty() {
        givenSinglePage(List.of());

        BackfillResult result = service.backfillAll();

        verify(usersByGradeRedisRepository, never()).addUsersBatch(any());
        assertThat(result.success()).isTrue();
        assertThat(result.count()).isZero();
    }

    @Test
    @DisplayName("backfillAll - 알 수 없는 riskGrade 는 skip + WARN")
    void backfillAll_unknownGrade_skipped() {
        givenSinglePage(List.of(
                profile(1L, "STABLE"),
                profile(2L, "UNKNOWN_VALUE"),
                profile(3L, null)
        ));

        BackfillResult result = service.backfillAll();

        assertThat(result.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("backfillAll - DB 조회 예외 시 failed result")
    void backfillAll_dbException() {
        when(investmentProfileRepository.findAll(any(Pageable.class)))
                .thenThrow(new RuntimeException("db down"));

        BackfillResult result = service.backfillAll();

        verify(usersByGradeRedisRepository, never()).addUsersBatch(any());
        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("db down");
    }

    @Test
    @DisplayName("backfillAll - Redis 적재 예외 시 failed result (DB 실패와 대칭)")
    void backfillAll_redisException() {
        givenSinglePage(List.of(profile(1L, "STABLE")));
        doThrow(new RuntimeException("redis down"))
                .when(usersByGradeRedisRepository).addUsersBatch(any());

        BackfillResult result = service.backfillAll();

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("redis down");
    }

    /** 첫 페이지에 모든 데이터, 다음 페이지 없음을 mock — paging loop 1회만 돌고 종료. */
    private void givenSinglePage(List<InvestmentProfile> profiles) {
        Page<InvestmentProfile> page = new PageImpl<>(profiles);
        when(investmentProfileRepository.findAll(any(Pageable.class))).thenReturn(page);
    }

    private static InvestmentProfile profile(Long userId, String riskGrade) {
        return InvestmentProfile.builder()
                .userId(userId)
                .riskScore(50L)
                .riskGrade(riskGrade)
                .answersSnapshot(Map.of())
                .build();
    }
}
