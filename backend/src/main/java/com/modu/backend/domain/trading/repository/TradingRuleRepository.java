package com.modu.backend.domain.trading.repository;

import com.modu.backend.domain.trading.entity.TradingRule;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TradingRuleRepository extends JpaRepository<TradingRule, Long> {

    /** 매매 룰셋 설정 완료 여부 확인 (온보딩 체크용) */
    boolean existsByUserId(Long userId);

    /**
     * 사용자 룰 row 에 PESSIMISTIC_WRITE 잠금을 걸어 조회 — AI 운용 한도 hard rule
     * 검증 시 "오늘 누적 조회 + 단일 주문 INSERT" 가 같은 트랜잭션 안에서 직렬화되도록 함.
     *
     * 같은 사용자의 동시 BUY 요청은 첫 트랜잭션 commit 까지 대기 → 두 번째는
     * 갱신된 누적값으로 검증 → race 우회 차단.
     *
     * Postgres 의 SELECT ... FOR UPDATE 로 매핑됨. @Transactional 컨텍스트 필수.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM TradingRule r WHERE r.userId = :userId")
    Optional<TradingRule> findByUserIdForUpdate(@Param("userId") Long userId);
}
