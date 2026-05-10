package com.modu.backend.domain.trading.service;

import com.modu.backend.domain.trading.client.KisOrderClient;
import com.modu.backend.domain.trading.dto.OrderRequest;
import com.modu.backend.domain.trading.dto.OrderResponse;
import com.modu.backend.domain.trading.entity.*;
import com.modu.backend.domain.trading.exception.OrderErrorCode;
import com.modu.backend.domain.trading.repository.OrderRepository;
import com.modu.backend.domain.trading.repository.TradingRuleRepository;
import com.modu.backend.domain.user.entity.KisCredential;
import com.modu.backend.domain.user.exception.UserErrorCode;
import com.modu.backend.domain.user.repository.KisCredentialRepository;
import com.modu.backend.domain.user.service.KisTokenService;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.error.CommonErrorCode;
import com.modu.backend.global.util.AesGcmEncryptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.UUID;

/**
 * 수동 주문 실행 서비스
 *
 * [처리 흐름]
 * 1. Idempotency-Key 중복 확인 → 기존 응답 반환
 * 2. KIS 연동 확인 (미연동 → KIS_NOT_CONNECTED)
 * 3. 모의투자 계좌 차단
 * 4. 장 운영 시간 확인 (평일 09:00~15:30 KST)
 * 5. 일일 누적 매수 금액 한도 확인 (매수 주문에만 적용)
 * 6. 잔고 확인 (KIS inquire-psbl-order / inquire-psbl-sell)
 * 7. DB에 PENDING 상태 주문 먼저 저장 (KIS 호출 전 로컬 기록 확보)
 * 8. KIS 주문 실행
 * 9. KIS 응답으로 주문 정보 업데이트
 *
 * [Idempotency 동시성 전략]
 * - 순차 중복: 1단계 선조회로 처리
 * - 동시 중복: orders(user_id, idempotency_key) DB 유니크 제약이 최후 방어선.
 *   DataIntegrityViolationException 발생 시 기존 주문을 재조회해 반환.
 *
 * [DB 먼저 저장 이유]
 * KIS 주문 성공 후 DB 저장 실패 시 외부 주문만 남는 불일치를 방지.
 * 저장 실패 시 KIS 호출이 차단되므로 역방향 불일치는 발생하지 않음.
 *
 * [트랜잭션 설계]
 * 토큰 갱신: KisTokenService 자체 @Transactional에서 처리
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalTime MARKET_OPEN  = LocalTime.of(9, 0);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

    private final OrderRepository orderRepository;
    private final KisCredentialRepository kisCredentialRepository;
    private final TradingRuleRepository tradingRuleRepository;
    private final KisTokenService kisTokenService;
    private final KisOrderClient kisOrderClient;
    private final AesGcmEncryptor encryptor;

    @Transactional
    public OrderResponse placeOrder(Long userId, OrderRequest request, String idempotencyKey) {
        String resolvedKey = (idempotencyKey != null && !idempotencyKey.isBlank())
                ? idempotencyKey
                : UUID.randomUUID().toString();

        // 1단계: 선조회 — 순차 중복 요청 빠른 반환
        return orderRepository.findByUserIdAndIdempotencyKey(userId, resolvedKey)
                .map(OrderResponse::from)
                .orElseGet(() -> doPlaceOrder(userId, request, resolvedKey));
    }

    private OrderResponse doPlaceOrder(Long userId, OrderRequest request, String idempotencyKey) {
        KisCredential credential = kisCredentialRepository.findByUserId(userId)
                .orElseThrow(() -> new ApiException(UserErrorCode.KIS_NOT_CONNECTED));

        if (!credential.isRealAccount()) {
            throw new ApiException(UserErrorCode.KIS_MOCK_ACCOUNT_NOT_SUPPORTED);
        }

        validateMarketHours();
        validateDailyOrderLimit(userId, request);

        String appKey      = encryptor.decrypt(credential.getAppKeyEnc());
        String appSecret   = encryptor.decrypt(credential.getAppSecretEnc());
        String accessToken = kisTokenService.getOrIssueAccessToken(userId, appKey, appSecret);

        validateBalance(accessToken, appKey, appSecret,
                credential.getAccountNo(), credential.getAccountPrdtCd(), request);

        // KIS 호출 전 PENDING 상태로 먼저 저장
        // → DB 저장 성공 이후 KIS 호출하므로 "KIS 성공 + DB 저장 실패" 불일치 방지
        Order order = Order.builder()
                .userId(userId)
                .stockCode(request.stockCode())
                .side(request.side())
                .orderType(request.orderMethod())
                .quantity((long) request.quantity())
                .limitPrice(request.price())
                .status(OrderStatus.PENDING)
                .source(OrderSource.MANUAL)
                .idempotencyKey(idempotencyKey)
                .build();

        try {
            orderRepository.saveAndFlush(order);
        } catch (DataIntegrityViolationException e) {
            // 동시 중복 요청이 유니크 제약 충돌 → 먼저 저장된 주문 반환
            log.warn("Idempotency 중복 충돌 감지 - userId: {}, key: {}", userId, idempotencyKey);
            return orderRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                    .map(OrderResponse::from)
                    .orElseThrow(() -> new ApiException(CommonErrorCode.CONCURRENT_CONFLICT));
        }

        // KIS 주문 실행
        KisOrderClient.KisOrderResult result = kisOrderClient.placeOrder(
                accessToken, appKey, appSecret,
                credential.getAccountNo(), credential.getAccountPrdtCd(),
                request.stockCode(), request.side(), request.orderMethod(),
                request.quantity(), request.price()
        );

        // KIS 응답으로 주문 정보 업데이트 (JPA dirty checking으로 자동 반영)
        order.updateKisInfo(result.kisOrderNo(), result.kisOrgNo(), OffsetDateTime.now());

        log.info("수동 주문 접수 완료 - userId: {}, stockCode: {}, side: {}, kisOrderNo: {}",
                userId, request.stockCode(), request.side(), result.kisOrderNo());

        return OrderResponse.from(order);
    }

    private void validateMarketHours() {
        ZonedDateTime now = ZonedDateTime.now(KST);
        DayOfWeek day     = now.getDayOfWeek();
        LocalTime time    = now.toLocalTime();

        boolean isWeekend    = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
        boolean isMarketOpen = time.isAfter(MARKET_OPEN) && time.isBefore(MARKET_CLOSE);

        if (isWeekend || !isMarketOpen) {
            throw new ApiException(OrderErrorCode.MARKET_CLOSED);
        }
    }

    private void validateDailyOrderLimit(Long userId, OrderRequest request) {
        if (request.side() != OrderSide.BUY) return;

        tradingRuleRepository.findById(userId).ifPresent(rule -> {
            long todayTotal     = orderRepository.sumTodayBuyAmount(userId);
            long newOrderAmount = (long) request.quantity() * request.price();

            if (todayTotal + newOrderAmount > rule.getDailyLossLimitAmount()) {
                throw new ApiException(OrderErrorCode.DAILY_ORDER_LIMIT_EXCEEDED);
            }
        });
    }

    private void validateBalance(String accessToken, String appKey, String appSecret,
                                 String cano, String acntPrdtCd, OrderRequest request) {
        if (request.side() == OrderSide.BUY) {
            long buyable     = kisOrderClient.getBuyableAmount(
                    accessToken, appKey, appSecret, cano, acntPrdtCd,
                    request.stockCode(), request.price());
            long orderAmount = (long) request.quantity() * request.price();
            if (orderAmount > buyable) {
                throw new ApiException(OrderErrorCode.INSUFFICIENT_BALANCE);
            }
        } else {
            long sellable = kisOrderClient.getSellableQuantity(
                    accessToken, appKey, appSecret, cano, acntPrdtCd,
                    request.stockCode(), request.price());
            if (request.quantity() > sellable) {
                throw new ApiException(OrderErrorCode.INSUFFICIENT_BALANCE);
            }
        }
    }
}
