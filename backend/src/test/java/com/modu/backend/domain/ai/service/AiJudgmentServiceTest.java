package com.modu.backend.domain.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modu.backend.domain.ai.dto.AiJudgmentDetailResponse;
import com.modu.backend.domain.ai.dto.AiJudgmentPageResponse;
import com.modu.backend.domain.ai.entity.AiJudgment;
import com.modu.backend.domain.ai.exception.AiErrorCode;
import com.modu.backend.domain.ai.repository.AiJudgmentRepository;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.error.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiJudgmentServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long ORDER_ID = 5001L;

    @Mock
    AiJudgmentRepository aiJudgmentRepository;

    @InjectMocks
    AiJudgmentService aiJudgmentService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("AI 판단 전체 이력 조회 성공")
    void getJudgments_success() {
        // given
        PageRequest pageable = PageRequest.of(0, 20, Sort.by("judgedAt").descending());
        AiJudgment judgment = judgment(101L, USER_ID, ORDER_ID, "005930", "PASSED");

        when(aiJudgmentRepository.findByUserIdOrderByJudgedAtDesc(eq(USER_ID), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(judgment), pageable, 1));

        // when
        AiJudgmentPageResponse result = aiJudgmentService.getJudgments(USER_ID, 0, 20);

        // then
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).judgmentId()).isEqualTo(101L);
        assertThat(result.content().get(0).eventType()).isEqualTo("PASSED");
        assertThat(result.page()).isEqualTo(0);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.hasNext()).isFalse();
        verify(aiJudgmentRepository).findByUserIdOrderByJudgedAtDesc(eq(USER_ID), eq(pageable));
    }

    @Test
    @DisplayName("AI 판단 전체 이력 조회 결과가 없으면 빈 페이지를 반환한다")
    void getJudgments_empty() {
        // given
        PageRequest pageable = PageRequest.of(0, 20, Sort.by("judgedAt").descending());
        when(aiJudgmentRepository.findByUserIdOrderByJudgedAtDesc(eq(USER_ID), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        // when
        AiJudgmentPageResponse result = aiJudgmentService.getJudgments(USER_ID, 0, 20);

        // then
        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
    }

    @Test
    @DisplayName("주문별 AI 판단 근거 조회 성공")
    void getJudgmentByOrder_success() {
        // given
        AiJudgment judgment = judgment(101L, USER_ID, ORDER_ID, "005930", "PASSED");
        when(aiJudgmentRepository.findFirstByUserIdAndOrderIdOrderByJudgedAtDesc(USER_ID, ORDER_ID))
                .thenReturn(Optional.of(judgment));

        // when
        AiJudgmentDetailResponse result = aiJudgmentService.getJudgmentByOrder(USER_ID, ORDER_ID);

        // then
        assertThat(result.judgmentId()).isEqualTo(101L);
        assertThat(result.orderId()).isEqualTo(ORDER_ID);
        assertThat(result.stockCode()).isEqualTo("005930");
        assertThat(result.eventType()).isEqualTo("PASSED");
        assertThat(result.indicatorsSnapshot().get("rsi").asDouble()).isEqualTo(48.2);
    }

    @Test
    @DisplayName("주문별 AI 판단 근거가 없으면 AI_001 예외를 던진다")
    void getJudgmentByOrder_notFound() {
        // given
        when(aiJudgmentRepository.findFirstByUserIdAndOrderIdOrderByJudgedAtDesc(USER_ID, ORDER_ID))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> aiJudgmentService.getJudgmentByOrder(USER_ID, ORDER_ID))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo(AiErrorCode.JUDGMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("다른 사용자의 주문별 AI 판단 근거는 조회되지 않는다")
    void getJudgmentByOrder_otherUserNotFound() {
        // given
        when(aiJudgmentRepository.findFirstByUserIdAndOrderIdOrderByJudgedAtDesc(OTHER_USER_ID, ORDER_ID))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> aiJudgmentService.getJudgmentByOrder(OTHER_USER_ID, ORDER_ID))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo(AiErrorCode.JUDGMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("페이지 번호가 음수이면 검증 예외를 던진다")
    void getJudgments_invalidPage() {
        assertThatThrownBy(() -> aiJudgmentService.getJudgments(USER_ID, -1, 20))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("페이지 크기가 허용 범위를 벗어나면 검증 예외를 던진다")
    void getJudgments_invalidSize() {
        assertThatThrownBy(() -> aiJudgmentService.getJudgments(USER_ID, 0, 101))
                .isInstanceOf(ValidationException.class);
    }

    private AiJudgment judgment(Long id, Long userId, Long orderId, String stockCode, String decision) {
        JsonNode indicatorsSnapshot = objectMapper.createObjectNode()
                .put("rsi", 48.2)
                .put("macd", "golden_cross")
                .put("volumeChangeRate", 18.5);

        AiJudgment judgment = AiJudgment.builder()
                .userId(userId)
                .stockCode(stockCode)
                .orderId(orderId)
                .decision(decision)
                .confidenceScore(82L)
                .indicatorsSnapshot(indicatorsSnapshot)
                .judgmentReason("거래량 증가와 단기 추세 개선으로 매수 조건을 충족했습니다.")
                .judgedAt(OffsetDateTime.parse("2026-05-08T09:00:00+09:00"))
                .build();

        ReflectionTestUtils.setField(judgment, "id", id);
        return judgment;
    }
}
