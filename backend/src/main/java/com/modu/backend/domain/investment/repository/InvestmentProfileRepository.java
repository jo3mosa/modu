package com.modu.backend.domain.investment.repository;

import com.modu.backend.domain.investment.entity.InvestmentProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestmentProfileRepository extends JpaRepository<InvestmentProfile, Long> {

    /** 투자 성향 설문 완료 여부 확인 (온보딩 체크용) */
    boolean existsByUserId(Long userId);
}
