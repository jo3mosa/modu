package com.modu.backend.domain.trading.service;

import com.modu.backend.domain.strategy.service.KillSwitchService;
import com.modu.backend.domain.trading.calendar.policy.MarketHourPhase;
import com.modu.backend.domain.trading.calendar.policy.MarketHourPolicy;
import com.modu.backend.domain.trading.client.KisOrderClient;
import com.modu.backend.domain.trading.client.KisReservedOrderClient;
import com.modu.backend.domain.trading.entity.Order;
import com.modu.backend.domain.trading.entity.OrderSource;
import com.modu.backend.domain.trading.entity.OrderStatus;
import com.modu.backend.domain.trading.repository.OrderRepository;
import com.modu.backend.domain.trading.sse.OrderSseEmitterManager;
import com.modu.backend.domain.trading.sse.OrderSseEvent;
import com.modu.backend.domain.user.entity.KisCredential;
import com.modu.backend.domain.user.exception.UserErrorCode;
import com.modu.backend.domain.user.repository.KisCredentialRepository;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.error.CommonErrorCode;
import com.modu.backend.global.error.ErrorCode;
import com.modu.backend.global.kis.KisApiCallTemplate;
import com.modu.backend.global.util.AesGcmEncryptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 주문 디스패치 — 단일 진입점 (S14P31B106-336)
 *
 * 정규장 / 예약 가능 / 대기 / 거절 분기를 모두 처리. KisOrderConsumer 와 ReservedPendingOrderSweeper
 * 둘 다 본 서비스 호출.
 *
 * [라우팅 (자동매매 source 한정)]
 *  - REGULAR                     → KIS placeOrder (기존 동작)
 *  - RESERVED_AVAILABLE          → KIS placeReservedOrder (실 계좌만, 모의는 reject)
 *  - WAITING_FOR_RESERVED_WINDOW → order.markReservedPending + SSE (실 계좌만, 모의는 reject)
 *  - REJECT                      → order.reject (시스템 초기화 / F gap)
 *  수동 주문(MANUAL)은 OrderService 가 사전 시간 검증으로 정규장만 통과시키므로 별도 분기 불필요.
 *
 * [Kill Switch 카운트 정책]
 *  - 자동매매 source + KIS 응답성 거부 (OrderErrorCode / EXTERNAL_API_ERROR) → recordReject
 *  - 시간/계좌 인프라성 reject (REJECT phase, 모의계좌) → 카운트 제외
 *  - 정상 접수 (PENDING / RESERVED) → recordSuccess (자동매매 한정)
 *
 * [동시성]
 *  Pessimistic write lock 으로 동일 orderId 동시 처리 race 차단. Consumer 와 Sweeper 가 동일 row 를
 *  잡아도 두 번째 호출은 status 가 이미 전이되어 self-skip.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderDispatchService {

    private final OrderRepository orderRepository;
    private final KisCredentialRepository kisCredentialRepository;
    private final AesGcmEncryptor encryptor;
    private final KisApiCallTemplate kisApiCallTemplate;
    private final KisOrderClient kisOrderClient;
    private final KisReservedOrderClient kisReservedOrderClient;
    private final OrderSseEmitterManager sseEmitterManager;
    private final KillSwitchService killSwitchService;
    private final MarketHourPolicy marketHourPolicy;

    /**
     * orderId 로 Order 를 락 잡아 로드한 뒤 라우팅 후 처리.
     * 한 번의 호출 = 한 트랜잭션 = 한 OrderId. Sweeper 가 N개 처리 시 N번 호출 (각각 자체 tx).
     */
    @Transactional
    public void dispatch(Long orderId) {
        Optional<Order> opt = orderRepository.findByIdForUpdate(orderId);
        if (opt.isEmpty()) {
            log.warn("[Dispatch] orderId not found - skip. orderId: {}", orderId);
            return;
        }
        Order order = opt.get();

        // 이미 종착 상태면 skip (중복 dispatch 가드)
        if (isTerminal(order.getStatus())) {
            log.info("[Dispatch] 종착 상태 — skip. orderId: {}, status: {}", orderId, order.getStatus());
            return;
        }

        Long userId = order.getUserId();
        String stockCode = order.getStockCode();
        OrderSource source = order.getSource();

        // 1) KIS 자격증명 준비
        KisCredential credential;
        String appKey;
        String appSecret;
        try {
            credential = kisCredentialRepository.findByUserId(userId)
                    .orElseThrow(() -> new ApiException(UserErrorCode.KIS_NOT_CONNECTED));
            appKey    = encryptor.decrypt(credential.getAppKeyEnc());
            appSecret = encryptor.decrypt(credential.getAppSecretEnc());
        } catch (IllegalStateException e) {
            handleKisFailure(order, source, new ApiException(UserErrorCode.KIS_CREDENTIAL_DECRYPT_FAILED, e));
            return;
        } catch (ApiException e) {
            handleKisFailure(order, source, e);
            return;
        }

        // 2) 시간/개장일 분류
        MarketHourPhase phase = marketHourPolicy.classify(OffsetDateTime.now());
        log.info("[Dispatch] orderId: {}, source: {}, phase: {}, isRealAccount: {}",
                orderId, source, phase, credential.isRealAccount());

        // 3) 분기
        switch (phase) {
            case REGULAR -> handlePlaceOrder(order, credential, appKey, appSecret, source);
            case RESERVED_AVAILABLE -> {
                if (!credential.isRealAccount()) {
                    rejectMockAccount(order, stockCode);
                    return;
                }
                handlePlaceReservedOrder(order, credential, appKey, appSecret, source);
            }
            case WAITING_FOR_RESERVED_WINDOW -> {
                if (!credential.isRealAccount()) {
                    rejectMockAccount(order, stockCode);
                    return;
                }
                if (order.getStatus() != OrderStatus.RESERVED_PENDING) {
                    order.markReservedPending();
                    sseEmitterManager.send(userId,
                            OrderSseEvent.reservedPending(String.valueOf(order.getId()), stockCode));
                    log.info("[Dispatch] RESERVED_PENDING 전이 - orderId: {}", orderId);
                }
                // 이미 RESERVED_PENDING 이면 자연 noop — 다음 사이클 대기
            }
            case REJECT -> rejectTimeWindow(order, stockCode);
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // 분기 핸들러
    // ───────────────────────────────────────────────────────────────────

    private void handlePlaceOrder(Order order, KisCredential credential,
                                  String appKey, String appSecret, OrderSource source) {
        Long userId = order.getUserId();
        String stockCode = order.getStockCode();

        KisOrderClient.KisOrderResult result;
        try {
            result = kisApiCallTemplate.callWithTokenRetry(userId, appKey, appSecret,
                    token -> kisOrderClient.placeOrder(
                            token, appKey, appSecret,
                            credential.getAccountNo(), credential.getAccountPrdtCd(),
                            stockCode, order.getSide(), order.getOrderType(),
                            order.getQuantity(), order.getLimitPrice() != null ? order.getLimitPrice() : 0L));
        } catch (ApiException e) {
            handleKisFailure(order, source, e);
            return;
        }

        order.updateKisInfo(result.kisOrderNo(), result.kisOrgNo(), OffsetDateTime.now());
        log.info("[Dispatch] KIS 일반 주문 접수 - orderId: {}, kisOrderNo: {}",
                order.getId(), result.kisOrderNo());
        sseEmitterManager.send(userId,
                OrderSseEvent.submitted(String.valueOf(order.getId()), stockCode, result.kisOrderNo()));
        recordSuccessIfAutoTrade(source, userId, stockCode);
    }

    private void handlePlaceReservedOrder(Order order, KisCredential credential,
                                          String appKey, String appSecret, OrderSource source) {
        Long userId = order.getUserId();
        String stockCode = order.getStockCode();

        KisReservedOrderClient.KisReservedOrderResult result;
        try {
            result = kisApiCallTemplate.callWithTokenRetry(userId, appKey, appSecret,
                    token -> kisReservedOrderClient.placeReservedOrder(
                            token, appKey, appSecret,
                            credential.getAccountNo(), credential.getAccountPrdtCd(),
                            stockCode, order.getSide(), order.getOrderType(),
                            order.getQuantity(), order.getLimitPrice() != null ? order.getLimitPrice() : 0L));
        } catch (ApiException e) {
            handleKisFailure(order, source, e);
            return;
        }

        order.markReserved(result.rsvnOrdSeq(), OffsetDateTime.now());
        log.info("[Dispatch] KIS 예약주문 접수 - orderId: {}, rsvnOrdSeq: {}",
                order.getId(), result.rsvnOrdSeq());
        sseEmitterManager.send(userId,
                OrderSseEvent.reserved(String.valueOf(order.getId()), stockCode, result.rsvnOrdSeq()));
        recordSuccessIfAutoTrade(source, userId, stockCode);
    }

    private void rejectMockAccount(Order order, String stockCode) {
        String reason = "모의계좌는 KIS 예약주문을 지원하지 않습니다.";
        order.reject(reason);
        log.warn("[Dispatch] 모의계좌 예약주문 거절 - orderId: {}", order.getId());
        sseEmitterManager.send(order.getUserId(),
                OrderSseEvent.failed(String.valueOf(order.getId()), stockCode, reason));
        // Kill Switch 카운트 X — 인프라성 reject
    }

    private void rejectTimeWindow(Order order, String stockCode) {
        String reason = "주문 가능 시간 외 (시스템 초기화 또는 정규장 시작 전) — 주문이 거절되었습니다.";
        order.reject(reason);
        log.warn("[Dispatch] 주문 가능 시간 외 거절 - orderId: {}", order.getId());
        sseEmitterManager.send(order.getUserId(),
                OrderSseEvent.failed(String.valueOf(order.getId()), stockCode, reason));
        // Kill Switch 카운트 X — 인프라성 reject
    }

    /**
     * KIS 호출 실패 / 자격증명 오류 처리 — Kill Switch 카운트 정책은 isKisRejectError 화이트리스트 유지.
     */
    private void handleKisFailure(Order order, OrderSource source, ApiException e) {
        String reason = e.getErrorCode().getDefaultMessage();
        order.reject(reason);
        log.warn("[Dispatch] KIS 호출 실패 — REJECTED. orderId: {}, code: {}, reason: {}",
                order.getId(), e.getErrorCode().getCode(), reason);
        sseEmitterManager.send(order.getUserId(),
                OrderSseEvent.failed(String.valueOf(order.getId()), order.getStockCode(), reason));
        if (isAutoTradeSource(source) && isKisRejectError(e.getErrorCode())) {
            killSwitchService.recordReject(order.getUserId(), order.getStockCode(), reason);
        }
    }

    private void recordSuccessIfAutoTrade(OrderSource source, Long userId, String stockCode) {
        if (isAutoTradeSource(source)) {
            killSwitchService.recordSuccess(userId, stockCode);
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // 분류 헬퍼
    // ───────────────────────────────────────────────────────────────────

    private static boolean isTerminal(OrderStatus status) {
        return status == OrderStatus.FILLED
                || status == OrderStatus.CANCELED
                || status == OrderStatus.REJECTED
                || status == OrderStatus.RESERVED;
    }

    private static boolean isAutoTradeSource(OrderSource source) {
        return source == OrderSource.AI_DECISION
                || source == OrderSource.STOP_LOSS
                || source == OrderSource.TAKE_PROFIT;
    }

    /**
     * KIS 응답성 거부 — Kill Switch 카운트 대상.
     * 인프라/설정 장애 (KIS_NOT_CONNECTED, KIS_CREDENTIAL_DECRYPT_FAILED, KIS_TOKEN_INVALIDATED) 는 제외.
     */
    private static boolean isKisRejectError(ErrorCode errorCode) {
        return errorCode instanceof com.modu.backend.domain.trading.exception.OrderErrorCode
                || errorCode == CommonErrorCode.EXTERNAL_API_ERROR;
    }
}
