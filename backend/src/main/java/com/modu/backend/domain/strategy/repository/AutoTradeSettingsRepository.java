package com.modu.backend.domain.strategy.repository;

import com.modu.backend.domain.strategy.entity.AutoTradeSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AutoTradeSettingsRepository extends JpaRepository<AutoTradeSettings, Long> {
}
