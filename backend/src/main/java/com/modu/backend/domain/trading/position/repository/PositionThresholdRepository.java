package com.modu.backend.domain.trading.position.repository;

import com.modu.backend.domain.trading.position.entity.PositionThreshold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface PositionThresholdRepository extends JpaRepository<PositionThreshold, Long> {

    /**
     * Position Monitor 폴링 대상 조회
     * is_active=TRUE AND (active_target_price NOT NULL OR active_stop_loss_price NOT NULL)
     * (스키마 CHK_ACTIVE_PRICES 제약상 활성 row 는 둘 중 하나는 보장되지만, 명시적 필터로 NULL skip 안전망)
     */
    @Query("""
        SELECT p FROM PositionThreshold p
        WHERE p.isActive = true
          AND (p.activeStopLossPrice IS NOT NULL OR p.activeTargetPrice IS NOT NULL)
    """)
    List<PositionThreshold> findAllActiveForMonitor();

    /**
     * 자동 구독 대상 종목 코드 집합 — 부팅 시 일괄 구독용
     */
    @Query("""
        SELECT DISTINCT p.stockCode FROM PositionThreshold p
        WHERE p.isActive = true
    """)
    Set<String> findActiveStockCodes();

    /**
     * 전량 매도 시 해당 종목에 다른 사용자의 활성 임계가 남아있는지 확인 — 자동 구독 해제 가드용
     */
    boolean existsByStockCodeAndIsActiveTrue(String stockCode);

    /**
     * 활성 row 단건 조회 — partial unique index (user_id, stock_code) WHERE is_active=TRUE 가 1개 보장
     * AI 판단 수신 시 ai_*_price 갱신용 (S14P31B106-263)
     */
    Optional<PositionThreshold> findByUserIdAndStockCodeAndIsActiveTrue(Long userId, String stockCode);

    /**
     * 사용자별 활성 보유 종목 코드 집합 — gateway 의 UserKisSession 부팅 자동 구독용 (S14P31B106-345)
     */
    @Query("""
        SELECT DISTINCT p.stockCode FROM PositionThreshold p
        WHERE p.userId = :userId
          AND p.isActive = true
    """)
    Set<String> findActiveStockCodesByUserId(Long userId);

    /**
     * 268 backfill — position:index Redis 키 복원용 활성 (user_id, stock_code) 쌍 전체
     */
    @Query("""
        SELECT new com.modu.backend.domain.trading.position.repository.PositionThresholdRepository$UserStockPair(
            p.userId, p.stockCode)
        FROM PositionThreshold p
        WHERE p.isActive = true
    """)
    List<UserStockPair> findAllActivePairs();

    /**
     * 268 단계 4 KIS 검증 — 사용자별 활성 종목 코드 집합 (KIS 잔고와 비교용)
     */
    @Query("""
        SELECT DISTINCT p.stockCode FROM PositionThreshold p
        WHERE p.userId = :userId AND p.isActive = true
    """)
    Set<String> findActiveStockCodesByUserId(Long userId);

    /** (user_id, stock_code) 쌍 projection — 268 backfill 전용 */
    record UserStockPair(Long userId, String stockCode) {}
}
