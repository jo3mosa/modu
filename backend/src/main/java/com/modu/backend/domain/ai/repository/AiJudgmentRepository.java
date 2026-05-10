package com.modu.backend.domain.ai.repository;

import com.modu.backend.domain.ai.entity.AiJudgment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AiJudgmentRepository extends JpaRepository<AiJudgment, Long> {

    Page<AiJudgment> findByUserIdOrderByJudgedAtDesc(Long userId, Pageable pageable);

    Optional<AiJudgment> findFirstByUserIdAndOrderIdOrderByJudgedAtDesc(Long userId, Long orderId);
}
