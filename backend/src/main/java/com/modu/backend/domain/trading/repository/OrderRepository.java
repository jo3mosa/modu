package com.modu.backend.domain.trading.repository;

import com.modu.backend.domain.trading.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    /**
     * KIS 주문번호(kis_order_no) 목록으로 주문 일괄 조회
     * 미체결 주문 조회 시 KIS 응답 odno 와 우리 DB 주문을 조인할 때 사용
     */
    List<Order> findByUserIdAndKisOrderNoIn(Long userId, List<String> kisOrderNos);

    /**
     * 오늘 매수 주문(PENDING + FILLED)의 총 주문 금액 합산
     * 일일 누적 한도 초과 여부 검증에 사용
     */
    @Query(value = """
            SELECT COALESCE(SUM(limit_price * quantity), 0)
            FROM orders
            WHERE user_id = :userId
              AND side = 'BUY'
              AND status IN ('PENDING', 'FILLED')
              AND DATE(created_at AT TIME ZONE 'Asia/Seoul') = CURRENT_DATE
            """, nativeQuery = true)
    Long sumTodayBuyAmount(@Param("userId") Long userId);
}
