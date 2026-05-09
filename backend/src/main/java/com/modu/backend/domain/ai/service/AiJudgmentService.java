package com.modu.backend.domain.ai.service;

import com.modu.backend.domain.ai.dto.AiJudgmentDetailResponse;
import com.modu.backend.domain.ai.dto.AiJudgmentPageResponse;
import com.modu.backend.domain.ai.dto.AiJudgmentSummaryResponse;
import com.modu.backend.domain.ai.exception.AiErrorCode;
import com.modu.backend.domain.ai.repository.AiJudgmentRepository;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.error.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AiJudgmentService {

    private static final int MAX_PAGE_SIZE = 100;

    private final AiJudgmentRepository aiJudgmentRepository;

    public AiJudgmentPageResponse getJudgments(Long userId, int page, int size) {
        validatePageRequest(page, size);

        PageRequest pageable = PageRequest.of(page, size, Sort.by("judgedAt").descending());
        Page<AiJudgmentSummaryResponse> judgments = aiJudgmentRepository
                .findByUserIdOrderByJudgedAtDesc(userId, pageable)
                .map(AiJudgmentSummaryResponse::from);

        return AiJudgmentPageResponse.from(judgments);
    }

    public AiJudgmentDetailResponse getJudgmentByOrder(Long userId, Long orderId) {
        return aiJudgmentRepository.findFirstByUserIdAndOrderIdOrderByJudgedAtDesc(userId, orderId)
                .map(AiJudgmentDetailResponse::from)
                .orElseThrow(() -> new ApiException(AiErrorCode.JUDGMENT_NOT_FOUND));
    }

    private void validatePageRequest(int page, int size) {
        if (page < 0) {
            throw new ValidationException("페이지 번호는 0 이상이어야 합니다.");
        }

        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ValidationException("페이지 크기는 1 이상 100 이하이어야 합니다.");
        }
    }
}
