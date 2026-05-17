package com.modu.backend.domain.trading.scheduler;

import com.modu.backend.domain.trading.entity.Order;
import com.modu.backend.domain.trading.entity.OrderStatus;
import com.modu.backend.domain.trading.repository.OrderRepository;
import com.modu.backend.domain.trading.service.OrderDispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 예약주문 발행 대기 폴링 스케줄러 (S14P31B106-336)
 *
 * E gap (15:30~15:40) 또는 공휴일 정규장 시간대에 진입한 RESERVED_PENDING 주문을 1분 주기로 폴링.
 * MarketHourPolicy 가 RESERVED_AVAILABLE / REGULAR / REJECT 로 판단하는 시점이 오면 dispatchService 가
 * 자연스럽게 적절한 처리 (예약주문 발행 / 일반 주문 / 거절) 진행.
 *
 * [주기]
 *  fixedDelay = 60_000ms — KIS rate limit 직렬화 + E gap 10분 / 정규장 도래 latency 1분 이내.
 *
 * [트랜잭션]
 *  목록 조회는 본 메서드에서 readOnly 가정 (saveAll 호출 없음). 각 row 처리는 dispatchService 가 자체 tx.
 *  부분 실패는 다음 사이클에서 재시도.
 *
 * [로컬 디버깅 토글]
 *  modu.reserved-pending-sweeper.enabled=false 로 비활성화 가능 (기본 true).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "modu.reserved-pending-sweeper.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ReservedPendingOrderSweeper {

    private final OrderRepository orderRepository;
    private final OrderDispatchService orderDispatchService;

    @Scheduled(fixedDelay = 60_000L)
    public void runCycle() {
        List<Order> pending = orderRepository.findByStatus(OrderStatus.RESERVED_PENDING);
        if (pending.isEmpty()) {
            return;
        }
        log.info("[예약 대기 스위퍼] 처리 대상: {}건", pending.size());
        for (Order order : pending) {
            try {
                orderDispatchService.dispatch(order.getId());
            } catch (Exception e) {
                log.error("[예약 대기 스위퍼] dispatch 실패 - orderId: {}", order.getId(), e);
                // 다음 사이클에서 재시도
            }
        }
    }
}
