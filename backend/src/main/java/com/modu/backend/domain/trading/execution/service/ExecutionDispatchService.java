package com.modu.backend.domain.trading.execution.service;

import com.modu.backend.domain.trading.entity.Order;
import com.modu.backend.domain.trading.entity.OrderSide;
import com.modu.backend.domain.trading.execution.parser.ExecutionPayload;
import com.modu.backend.domain.trading.kafka.producer.TradeOrderProducer;
import com.modu.backend.domain.trading.repository.OrderRepository;
import com.modu.backend.global.kafka.dto.TradeOrderExecutedMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * KIS 체결 통보 단건 디스패치 — S14P31B106-291
 *
 * WebSocket 핸들러가 복호화 + 파싱 완료한 ExecutionPayload 를 받아 Kafka 발행만 담당.
 * 실제 DB 업데이트 / SSE / Redis 갱신은 PortfolioUpdateConsumer 에서 처리.
 *
 * [흐름]
 *  1. ODER_NO 로 orders 단건 조회 (Pessimistic lock X — 빠르게 읽기만)
 *  2. KIS HHmmss → OffsetDateTime (today KST 기준 보강)
 *  3. TradeOrderExecutedMessage 빌드 → publishOrderExecuted
 *
 * [totalFilledQuantity / isFinalFill 의 best-effort]
 *  본 service 가 lock 없이 읽은 값이라 race window 존재. Consumer 가 Pessimistic lock 후 재계산 (authoritative).
 *  본 service 의 계산은 hint 용. 메시지 헤더 traceId 와 함께 디버깅 / 통계 보조.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionDispatchService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss");

    /** Redis dead-letter 키 — 운영자 수동 재처리 / 향후 retry 스케줄러 입력용 (followups). */
    private static final String UNMATCHED_KEY = "execution:unmatched";
    /** dead-letter TTL — 7일. 그 안에 reconcile 안 되면 영구 유실 인정 (운영 알람 권장). */
    private static final java.time.Duration UNMATCHED_TTL = java.time.Duration.ofDays(7);

    private final OrderRepository orderRepository;
    private final TradeOrderProducer tradeOrderProducer;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public void dispatch(ExecutionPayload payload) {
        Optional<Order> opt = orderRepository.findByKisOrderNo(payload.kisOrderNo());
        if (opt.isEmpty()) {
            // 우리 DB 에 없는 ODER_NO — 다른 채널 (KIS HTS 직접 거래) 또는 예약→일반 변환 미동기화 상황.
            // 단순 폐기 시 체결 영구 유실 위험. ERROR 로그 + Redis dead-letter 보관 → 운영자 reconcile 또는
            // followups 의 retry 스케줄러가 후속 처리.
            log.error("[ExecutionDispatch] kis_order_no 미매칭 — dead-letter 보관. kisOrderNo: {}",
                    payload.kisOrderNo());
            persistUnmatched(payload);
            return;
        }
        Order order = opt.get();

        long prevFilled = order.getFilledQuantity() == null ? 0L : order.getFilledQuantity();
        long hintTotalFilled = prevFilled + payload.cntgQty();
        boolean hintIsFinalFill = hintTotalFilled >= order.getQuantity();

        OffsetDateTime executedAt = resolveExecutedAt(payload.cntgHHMMSS());

        TradeOrderExecutedMessage message = TradeOrderExecutedMessage.of(
                order.getId(),
                order.getKisOrderNo(),
                order.getUserId(),
                order.getStockCode(),
                toOrderSide(payload.side()),
                payload.cntgQty(),
                payload.cntgUnpr(),
                hintTotalFilled,
                hintIsFinalFill,
                executedAt
        );

        tradeOrderProducer.publishOrderExecuted(message);
        log.info("[ExecutionDispatch] published - orderId: {}, side: {}, qty: {}, price: {}, hintFinal: {}",
                order.getId(), payload.side(), payload.cntgQty(), payload.cntgUnpr(), hintIsFinalFill);
    }

    /** KIS HHmmss + 오늘(KST) 날짜 결합. 자정 인근 경계 케이스는 receivedAt 보강으로 followups */
    private static OffsetDateTime resolveExecutedAt(String hhmmss) {
        try {
            LocalTime time = LocalTime.parse(hhmmss, HHMMSS);
            LocalDate today = LocalDate.now(KST);
            return LocalDateTime.of(today, time).atZone(KST).toOffsetDateTime();
        } catch (Exception e) {
            // 파싱 실패 시 현재 시각으로 대체
            return OffsetDateTime.now();
        }
    }

    private static OrderSide toOrderSide(ExecutionPayload.ExecutionSide side) {
        return switch (side) {
            case BUY -> OrderSide.BUY;
            case SELL -> OrderSide.SELL;
        };
    }

    /**
     * 미매칭 체결 보존 — Redis List 에 JSON push, 7일 TTL.
     * 운영자가 LRANGE 로 확인 / 향후 retry 스케줄러 (followups) 가 LPOP 으로 재처리 가능.
     */
    private void persistUnmatched(ExecutionPayload payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            redisTemplate.opsForList().rightPush(UNMATCHED_KEY, json);
            redisTemplate.expire(UNMATCHED_KEY, UNMATCHED_TTL);
        } catch (Exception e) {
            log.error("[ExecutionDispatch] dead-letter 보관 실패 - kisOrderNo: {}", payload.kisOrderNo(), e);
        }
    }
}
