package com.modu.backend.domain.trading.service;

import com.modu.backend.domain.trading.client.KisBuyingPowerClient;
import com.modu.backend.domain.trading.client.KisModifyOrderClient;
import com.modu.backend.domain.trading.client.KisOrderClient;
import com.modu.backend.domain.trading.client.KisPendingOrderClient;
import com.modu.backend.domain.trading.dto.BuyingPowerResponse;
import com.modu.backend.domain.trading.dto.ModifyOrderRequest;
import com.modu.backend.domain.trading.dto.ModifyOrderResponse;
import com.modu.backend.domain.trading.dto.OrderRequest;
import com.modu.backend.domain.trading.dto.OrderResponse;
import com.modu.backend.domain.trading.dto.PendingOrdersResponse;
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
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final KisPendingOrderClient kisPendingOrderClient;
    private final KisModifyOrderClient kisModifyOrderClient;
    private final KisBuyingPowerClient kisBuyingPowerClient;
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

        // KIS 주문 실행 (토큰 무효화 시 1회 재시도)
        KisOrderClient.KisOrderResult result;
        try {
            result = kisOrderClient.placeOrder(
                    accessToken, appKey, appSecret,
                    credential.getAccountNo(), credential.getAccountPrdtCd(),
                    request.stockCode(), request.side(), request.orderMethod(),
                    request.quantity(), request.price());
        } catch (ApiException e) {
            if (!CommonErrorCode.EXTERNAL_API_ERROR.equals(e.getErrorCode())) throw e;
            // EGW00202 등 — 외부에서 동일 appKey로 새 토큰 발급 시 DB 토큰이 무효화됨
            // 토큰 강제 재발급 후 1회 재시도 (KIS 분당 1회 제한에 걸리면 명확한 에러 반환)
            log.warn("[주문] KIS API 오류 → 토큰 강제 재발급 후 재시도 - userId: {}", userId);
            try {
                accessToken = kisTokenService.issueAndSaveAccessToken(userId, appKey, appSecret);
            } catch (ApiException tokenEx) {
                // EGW00133: KIS 분당 1회 발급 제한 — 외부에서 토큰을 발급한 직후 무효화된 상황
                log.warn("[주문] 토큰 재발급 실패 (rate limit 또는 자격증명 오류) - userId: {}", userId);
                throw new ApiException(OrderErrorCode.KIS_TOKEN_INVALIDATED);
            }
            result = kisOrderClient.placeOrder(
                    accessToken, appKey, appSecret,
                    credential.getAccountNo(), credential.getAccountPrdtCd(),
                    request.stockCode(), request.side(), request.orderMethod(),
                    request.quantity(), request.price());
        }

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
                    request.stockCode(), request.orderMethod(), request.price());
            long orderAmount = (long) request.quantity() * request.price();
            if (orderAmount > buyable) {
                throw new ApiException(OrderErrorCode.INSUFFICIENT_BALANCE);
            }
        } else {
            long sellable = kisOrderClient.getSellableQuantity(
                    accessToken, appKey, appSecret, cano, acntPrdtCd,
                    request.stockCode(), request.orderMethod(), request.price());
            if (request.quantity() > sellable) {
                throw new ApiException(OrderErrorCode.INSUFFICIENT_BALANCE);
            }
        }
    }

    // ── 미체결 주문 조회 ───────────────────────────────────────────────────────

    /**
     * 미체결 주문 목록 조회
     *
     * [처리 흐름]
     * 1. KIS 연동 확인
     * 2. KIS inquire-psbl-rvsecncl 호출 → 실시간 미체결 주문 목록 수신
     * 3. KIS odno 목록으로 우리 DB 주문 일괄 조회 (N+1 방지)
     * 4. KIS 데이터 + DB 데이터 병합 → 응답 구성
     *
     * [병합 전략]
     * KIS 응답 기준으로 목록을 구성하고, orders.kis_order_no = KIS odno 로 DB 주문을 조인.
     * DB 미매칭 주문(우리 시스템 외부에서 접수된 주문 등)은 orderId/source/createdAt 을 null 로 반환.
     *
     * [트랜잭션]
     * 읽기 전용 조회이므로 @Transactional 미사용. DB 커넥션 점유 최소화.
     */
    public PendingOrdersResponse getPendingOrders(Long userId) {
        KisCredential credential = kisCredentialRepository.findByUserId(userId)
                .orElseThrow(() -> new ApiException(UserErrorCode.KIS_NOT_CONNECTED));

        if (!credential.isRealAccount()) {
            throw new ApiException(UserErrorCode.KIS_MOCK_ACCOUNT_NOT_SUPPORTED);
        }

        String appKey      = encryptor.decrypt(credential.getAppKeyEnc());
        String appSecret   = encryptor.decrypt(credential.getAppSecretEnc());
        String accessToken = kisTokenService.getOrIssueAccessToken(userId, appKey, appSecret);

        // KIS 실시간 미체결 주문 목록 조회 (최대 50건)
        List<KisPendingOrderClient.KisPendingItem> kisItems = kisPendingOrderClient.getPendingOrders(
                accessToken, appKey, appSecret,
                credential.getAccountNo(), credential.getAccountPrdtCd()
        );

        if (kisItems.isEmpty()) {
            return new PendingOrdersResponse(Collections.emptyList());
        }

        // KIS odno 목록으로 DB 주문 일괄 조회 → Map<kisOrderNo, Order> 구성
        List<String> kisOrderNos = kisItems.stream()
                .map(KisPendingOrderClient.KisPendingItem::odno)
                .toList();

        Map<String, Order> orderByKisOrderNo = orderRepository
                .findByUserIdAndKisOrderNoIn(userId, kisOrderNos)
                .stream()
                .collect(Collectors.toMap(Order::getKisOrderNo, o -> o));

        // KIS 데이터와 DB 메타데이터 병합하여 응답 항목 구성
        List<PendingOrdersResponse.PendingOrderItem> items = kisItems.stream()
                .map(kisItem -> buildPendingOrderItem(kisItem, orderByKisOrderNo.get(kisItem.odno())))
                .toList();

        return new PendingOrdersResponse(items);
    }

    /**
     * KIS 미체결 항목 + DB 주문 메타데이터 → PendingOrderItem 변환
     *
     * @param kisItem KIS 응답 미체결 항목 (stockName, qty, filledQty 등 실시간 데이터)
     * @param order   DB 주문 엔티티 (orderId, source, createdAt 등 메타데이터). 없으면 null
     */
    private PendingOrdersResponse.PendingOrderItem buildPendingOrderItem(
            KisPendingOrderClient.KisPendingItem kisItem, Order order) {

        return new PendingOrdersResponse.PendingOrderItem(
                order != null ? String.valueOf(order.getId()) : null,
                kisItem.stockCode(),
                kisItem.stockName(),
                kisItem.side(),
                kisItem.orderType(),
                toIntExact(kisItem.quantity(), "quantity"),
                kisItem.price(),
                toIntExact(kisItem.filledQuantity(), "filledQuantity"),
                toIntExact(kisItem.remainQuantity(), "remainQuantity"),
                order != null ? order.getSource().name() : null,
                order != null ? order.getCreatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) : null
        );
    }

    /**
     * long → int 변환 (overflow 명시적 처리)
     * 주식 수량이 Integer.MAX_VALUE(약 21억)를 초과하는 경우는 현실적으로 없지만,
     * KIS API 응답 포맷 오류로 비정상적인 값이 올 경우를 방어
     */
    private int toIntExact(long value, String fieldName) {
        try {
            return Math.toIntExact(value);
        } catch (ArithmeticException e) {
            log.error("KIS 미체결 주문 수량 필드 int 범위 초과 - field: {}, value: {}", fieldName, value);
            throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR, e);
        }
    }

    // ── 미체결 주문 정정/취소 ─────────────────────────────────────────────────

    /**
     * 미체결 주문 정정 또는 취소
     *
     * [처리 흐름]
     * 1. DB에서 주문 조회 → 없으면 ORDER_NOT_FOUND
     * 2. 본인 주문 확인 → 불일치 시 ORDER_FORBIDDEN
     * 3. 정정/취소 가능 상태 확인 (PENDING, MODIFIED) → 그 외 ORDER_ALREADY_FILLED
     * 4. KIS 연동 확인, 토큰 준비
     * 5. KIS order-rvsecncl 호출
     * 6. DB 업데이트
     *    - MODIFY: 가격/수량 반영, 새 kis_order_no/kis_org_no 업데이트, status=MODIFIED
     *    - CANCEL: status=CANCELED, cancelled_at 기록
     *
     * [재정정 가능 설계]
     * 정정 후 KIS가 발급한 새 odno/krx_fwdg_ord_orgno 를 DB에 반드시 업데이트.
     * 이를 통해 정정된 주문을 다시 정정/취소할 수 있다.
     */
    @Transactional
    public ModifyOrderResponse modifyOrCancelOrder(Long userId, Long orderId, ModifyOrderRequest request) {
        // 1. 주문 조회
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ApiException(OrderErrorCode.ORDER_NOT_FOUND));

        // 2. 본인 주문 확인
        if (!order.getUserId().equals(userId)) {
            throw new ApiException(OrderErrorCode.ORDER_FORBIDDEN);
        }

        // 3. 정정/취소 가능 상태 확인 (PENDING, MODIFIED 만 허용)
        if (order.getStatus() != OrderStatus.PENDING
                && order.getStatus() != OrderStatus.MODIFIED) {
            throw new ApiException(OrderErrorCode.ORDER_ALREADY_FILLED);
        }

        // 4. KIS 연동 확인 및 토큰 준비
        KisCredential credential = kisCredentialRepository.findByUserId(userId)
                .orElseThrow(() -> new ApiException(UserErrorCode.KIS_NOT_CONNECTED));

        String appKey      = encryptor.decrypt(credential.getAppKeyEnc());
        String appSecret   = encryptor.decrypt(credential.getAppSecretEnc());
        String accessToken = kisTokenService.getOrIssueAccessToken(userId, appKey, appSecret);

        // 5. KIS 정정/취소 실행
        KisModifyOrderClient.KisModifyResult result = kisModifyOrderClient.execute(
                accessToken, appKey, appSecret,
                credential.getAccountNo(), credential.getAccountPrdtCd(),
                order.getKisOrgNo(), order.getKisOrderNo(),
                order.getOrderType(), request.action(),
                order.getQuantity(), order.getLimitPrice() != null ? order.getLimitPrice() : 0L,
                request.newQuantity(), request.newPrice()
        );

        // 6. DB 업데이트
        OffsetDateTime now = OffsetDateTime.now();
        if (request.action() == OrderModifyAction.MODIFY) {
            order.modify(
                    request.newPrice(),
                    request.newQuantity() != null ? (long) request.newQuantity() : null,
                    result.newKisOrderNo(),
                    result.newKisOrgNo()
            );
        } else {
            order.cancel(now);
        }

        log.info("주문 정정/취소 완료 - userId: {}, orderId: {}, action: {}, newKisOrderNo: {}",
                userId, orderId, request.action(), result.newKisOrderNo());

        return ModifyOrderResponse.from(order);
    }

    // ── 주문 가능 금액/수량 조회 ──────────────────────────────────────────────

    /**
     * 주문 가능 금액/수량 조회
     *
     * [처리 흐름]
     * 1. KIS 연동 확인 (미연동 → KIS_NOT_CONNECTED, 모의투자 → KIS_MOCK_ACCOUNT_NOT_SUPPORTED)
     * 2. SELL + stockCode 없음 검증 → SELL_REQUIRES_STOCK_CODE
     * 3. 복호화 및 토큰 준비
     * 4. inquire-psbl-order 호출 → maxBuyAmount + maxBuyQuantity + availableCash
     * 5. side=SELL 일 때만 inquire-psbl-sell 호출 → maxSellQuantity
     *    side=BUY 시 maxBuyQuantity=KIS값, maxSellQuantity=0
     *    side=SELL 시 maxBuyQuantity=0, maxSellQuantity=KIS값
     *
     * [트랜잭션]
     * 읽기 전용 조회이므로 @Transactional 미적용
     */
    public BuyingPowerResponse getBuyingPower(Long userId, String stockCode,
                                              OrderSide side, Long orderPrice) {
        KisCredential credential = kisCredentialRepository.findByUserId(userId)
                .orElseThrow(() -> new ApiException(UserErrorCode.KIS_NOT_CONNECTED));

        if (!credential.isRealAccount()) {
            throw new ApiException(UserErrorCode.KIS_MOCK_ACCOUNT_NOT_SUPPORTED);
        }

        // SELL 은 종목코드 필수 — 잘못된 요청에서 불필요한 복호화/토큰 발급 방지
        if (side == OrderSide.SELL && (stockCode == null || stockCode.isBlank())) {
            throw new ApiException(OrderErrorCode.SELL_REQUIRES_STOCK_CODE);
        }

        String appKey      = encryptor.decrypt(credential.getAppKeyEnc());
        String appSecret   = encryptor.decrypt(credential.getAppSecretEnc());
        String accessToken = kisTokenService.getOrIssueAccessToken(userId, appKey, appSecret);

        // inquire-psbl-order: maxBuyAmount + availableCash (side 무관하게 항상 호출)
        // stockCode=null 이면 PDNO/ORD_UNPR 공란으로 호출 → 매수금액+예수금만 조회 (수량 미제공)
        KisBuyingPowerClient.KisBuyPowerInfo buyPowerInfo = kisBuyingPowerClient.getBuyPowerInfo(
                accessToken, appKey, appSecret,
                credential.getAccountNo(), credential.getAccountPrdtCd(),
                stockCode, orderPrice
        );

        // inquire-psbl-sell: side=SELL 일 때만 호출 (stockCode 필수 보장됨)
        long maxSellQty = 0L;
        if (side == OrderSide.SELL) {
            maxSellQty = kisBuyingPowerClient.getSellableQuantity(
                    accessToken, appKey, appSecret,
                    credential.getAccountNo(), credential.getAccountPrdtCd(),
                    stockCode
            );
        }

        // SELL 시 maxBuyQuantity=0 (API 계약: BUY 시에만 제공)
        int maxBuyQty = side == OrderSide.SELL
                ? 0
                : toIntExact(buyPowerInfo.maxBuyQuantity(), "maxBuyQuantity");

        return new BuyingPowerResponse(
                buyPowerInfo.maxBuyAmount(),
                maxBuyQty,
                toIntExact(maxSellQty, "maxSellQuantity"),
                buyPowerInfo.availableCash()
        );
    }
}
