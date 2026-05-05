package com.modu.backend.domain.trading.repository;

import com.modu.backend.domain.trading.entity.TradingRule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradingRuleRepository extends JpaRepository<TradingRule, Long> {

    /** 매매 룰셋 설정 완료 여부 확인 (온보딩 체크용) */
    boolean existsByUserId(Long userId);
}
