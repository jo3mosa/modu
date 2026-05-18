package com.modu.backend.domain.trading.repository;

import com.modu.backend.domain.trading.entity.OrderExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * OrderExecution 접근 인터페이스 — S14P31B106-291
 *
 * 멱등성 ((order_id, kis_execution_no) UNIQUE) 은 DB CHECK 으로 보장.
 * 호출 측 (PortfolioUpdateConsumer) 가 DataIntegrityViolationException catch 시 "중복 메시지 — skip" 처리.
 */
public interface OrderExecutionRepository extends JpaRepository<OrderExecution, Long> {

    /**
     * 특정 주문의 모든 체결 단건 — trade_pnl_records INSERT 시 avg_buy_price 계산 용도 등.
     * 매수 주문 체결 단건 목록을 시간순 조회해 가중 평균 단가를 산출.
     */
    List<OrderExecution> findByOrderIdOrderByExecutedAtAsc(Long orderId);

    /**
     * 멱등성 사전 확인 — UNIQUE 제약과 함께 race 의 첫 단계로 사용.
     * SQL UNIQUE 가 최종 가드. 본 메서드는 hot path 의 사전 skip 으로만 활용.
     */
    boolean existsByOrderIdAndKisExecutionNo(Long orderId, String kisExecutionNo);
}
