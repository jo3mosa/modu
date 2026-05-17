package com.modu.backend.domain.ai.repository;

import com.modu.backend.domain.ai.entity.AiJudgment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AiJudgmentRepository extends JpaRepository<AiJudgment, Long> {

    Page<AiJudgment> findByUserIdOrderByJudgedAtDesc(Long userId, Pageable pageable);

    Optional<AiJudgment> findFirstByUserIdAndOrderIdOrderByJudgedAtDesc(Long userId, Long orderId);

    /**
     * 멱등 체크 — (user_id, source_event_id) 조합 중복 INSERT 사전 차단용 (S14P31B106-263)
     */
    boolean existsByUserIdAndSourceEventId(Long userId, String sourceEventId);

    /**
     * 승인 대기 목록 (S14P31B106-292)
     * execution_status = APPROVAL_REQUIRED + 미만료 (스케줄러가 만료 row 는 EXPIRED 로 전환)
     */
    java.util.List<AiJudgment> findByUserIdAndExecutionStatusOrderByJudgedAtDesc(
            Long userId, com.modu.backend.domain.ai.entity.AiExecutionStatus executionStatus);

    /**
     * 만료 스케줄러 (단계 9) 가 폴링할 만료 후보 (S14P31B106-292)
     */
    java.util.List<AiJudgment> findByExecutionStatusAndApprovalExpiresAtBefore(
            com.modu.backend.domain.ai.entity.AiExecutionStatus executionStatus,
            java.time.OffsetDateTime threshold);
}
