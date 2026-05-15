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
}
