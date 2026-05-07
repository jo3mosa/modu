package com.modu.backend.domain.investment.repository;

import com.modu.backend.domain.investment.entity.ProfileHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileHistoryRepository extends JpaRepository<ProfileHistory, Long> {
}
