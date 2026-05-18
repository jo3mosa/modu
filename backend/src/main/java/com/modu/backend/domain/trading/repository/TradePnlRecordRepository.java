package com.modu.backend.domain.trading.repository;

import com.modu.backend.domain.trading.entity.TradePnlRecord;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * TradePnlRecord 접근 인터페이스 — S14P31B106-291
 *
 * 회고 Agent 가 trade.settled 토픽으로 트리거되는 시점에 본 row 의 id 를 메시지에 실어 보냄.
 */
public interface TradePnlRecordRepository extends JpaRepository<TradePnlRecord, Long> {

    /** 매수 주문이 이미 PnL 매칭에 사용됐는지 — 동일 buy_order_id 중복 사용 방어 */
    boolean existsByBuyOrderId(Long buyOrderId);
}
