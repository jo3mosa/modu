package com.modu.backend.domain.trading.repository;

import com.modu.backend.domain.trading.entity.TradingRuleHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradingRuleHistoryRepository extends JpaRepository<TradingRuleHistory, Long> {
}
