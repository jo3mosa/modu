package com.modu.backend.domain.trading.kafka.consumer;

import com.modu.backend.domain.trading.client.KisOrderClient;
import com.modu.backend.domain.trading.entity.Order;
import com.modu.backend.domain.trading.entity.OrderSide;
import com.modu.backend.domain.trading.entity.OrderType;
import com.modu.backend.domain.trading.exception.OrderErrorCode;
import com.modu.backend.domain.trading.repository.OrderRepository;
import com.modu.backend.domain.trading.sse.OrderSseEmitterManager;
import com.modu.backend.domain.trading.sse.OrderSseEvent;
import com.modu.backend.domain.user.entity.KisCredential;
import com.modu.backend.domain.user.exception.UserErrorCode;
import com.modu.backend.domain.user.repository.KisCredentialRepository;
import com.modu.backend.domain.user.service.KisTokenService;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.error.CommonErrorCode;
import com.modu.backend.global.kafka.constant.KafkaConsumerGroup;
import com.modu.backend.global.kafka.constant.KafkaTopic;
import com.modu.backend.global.kafka.dto.TradeOrderMessage;
import com.modu.backend.global.util.AesGcmEncryptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * trade.order.submitted 토픽 소비자
 *
 * 수동/AI/손절익절 모든 주문이 본 Consumer 를 거쳐 KIS 에 실제 접수된다.
 * 본 Consumer 가 영구 컴포넌트로 자리잡아 OrderService 와 책임을 분리한다.
 *
 * [처리 흐름]
 * 1. orderId(= idempotencyKey) 로 PENDING Order 조회 — 없으면 메시지 무시 + ack
 * 2. kisOrderNo 이미 있으면 중복 처리로 보고 skip + ack
 * 3. KIS 자격증명·토큰 준비 — 없으면 REJECTED 처리 + SSE ORDER_FAILED + ack
 * 4. KisOrderClient.placeOrder 호출 (EGW00202 토큰 무효화 시 1회 재시도)
 * 5. 성공: order.updateKisInfo + SSE ORDER_SUBMITTED + ack
 * 6. 실패: order.reject(reason) + SSE ORDER_FAILED + ack
 *
 * [트랜잭션]
 * 메서드 전체 @Transactional — KIS 호출 동안 DB 커넥션을 점유하지만 KIS 응답 평균 100~300ms 라 부담 작음.
 * 운영 부하가 커지면 서비스 분리(KIS 호출 트랜잭션 밖)로 전환.
 *
 * [ack 정책]
 * 성공/실패/skip/예외 모두 ack — Consumer 가 ack 없이 빠져나가지 않음 (무한 재처리 차단)
 * DLQ 적재는 후순위 별도 이슈.
 *
 * [동시성]
 * KafkaConsumerConfig.kisOrderListenerContainerFactory — concurrency=1
 * KIS API rate limit 대응 직렬화. 부하 보고 점진 상향.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KisOrderConsumer {

    private final OrderRepository orderRepository;
    private final KisCredentialRepository kisCredentialRepository;
    private final KisTokenService kisTokenService;
    private final KisOrderClient kisOrderClient;
    private final AesGcmEncryptor encryptor;
    private final OrderSseEmitterManager sseEmitterManager;

    @Transactional
    @KafkaListener(
            topics = KafkaTopic.TRADE_ORDER_SUBMITTED,
            groupId = KafkaConsumerGroup.KIS_ORDER,
            containerFactory = "kisOrderListenerContainerFactory"
    )
    public void onMessage(TradeOrderMessage message, Acknowledgment ack) {
        try {
            processMessage(message);
        } catch (Exception e) {
            // processMessage 내부에서 명시적으로 처리하지 않은 예외 — 안전망
            log.error("KisOrderConsumer 미처리 예외 - orderId: {}", message.orderId(), e);
        } finally {
            ack.acknowledge();
        }
    }

    private void processMessage(TradeOrderMessage message) {
        Long userId        = message.userId();
        String orderId     = message.orderId();
        String stockCode   = message.stockCode();

        // 1) 주문 조회 (orderId = idempotencyKey)
        Optional<Order> optionalOrder = orderRepository.findByUserIdAndIdempotencyKey(userId, orderId);
        if (optionalOrder.isEmpty()) {
            log.error("주문 row 없음 — 메시지 무시. orderId: {}, userId: {}", orderId, userId);
            return;
        }
        Order order = optionalOrder.get();

        // 2) 중복 방어 — 이미 처리된 메시지 skip
        if (order.getKisOrderNo() != null) {
            log.warn("이미 처리된 주문 — skip. orderId: {}, kisOrderNo: {}", orderId, order.getKisOrderNo());
            return;
        }

        // 3) KIS 자격증명 / 토큰 준비
        KisCredential credential;
        String appKey;
        String appSecret;
        String accessToken;
        try {
            credential = kisCredentialRepository.findByUserId(userId)
                    .orElseThrow(() -> new ApiException(UserErrorCode.KIS_NOT_CONNECTED));
            appKey      = encryptor.decrypt(credential.getAppKeyEnc());
            appSecret   = encryptor.decrypt(credential.getAppSecretEnc());
            accessToken = kisTokenService.getOrIssueAccessToken(userId, appKey, appSecret);
        } catch (ApiException e) {
            handleFailure(order, userId, orderId, stockCode, e);
            return;
        }

        // 4) KIS placeOrder 호출 (토큰 무효화 시 1회 재시도) — OrderService 의 기존 로직 동등 이관
        KisOrderClient.KisOrderResult result;
        try {
            result = callKisPlaceOrder(message, credential, accessToken, appKey, appSecret);
        } catch (ApiException e) {
            if (CommonErrorCode.EXTERNAL_API_ERROR.equals(e.getErrorCode())) {
                // EGW00202 등 — 토큰 무효화 가능성. 재발급 후 1회 재시도
                log.warn("[Consumer] KIS API 오류 → 토큰 강제 재발급 후 재시도 - userId: {}", userId);
                try {
                    accessToken = kisTokenService.issueAndSaveAccessToken(userId, appKey, appSecret);
                    result = callKisPlaceOrder(message, credential, accessToken, appKey, appSecret);
                } catch (ApiException retryEx) {
                    // 재발급 자체 실패(EGW00133 rate limit 등) 또는 재시도 실패
                    ApiException finalError = CommonErrorCode.EXTERNAL_API_ERROR.equals(retryEx.getErrorCode())
                            ? new ApiException(OrderErrorCode.KIS_TOKEN_INVALIDATED)
                            : retryEx;
                    handleFailure(order, userId, orderId, stockCode, finalError);
                    return;
                }
            } else {
                handleFailure(order, userId, orderId, stockCode, e);
                return;
            }
        }

        // 5) 성공 처리 — order 갱신 + SSE ORDER_SUBMITTED
        order.updateKisInfo(result.kisOrderNo(), result.kisOrgNo(), OffsetDateTime.now());
        log.info("[Consumer] KIS 주문 접수 완료 - userId: {}, orderId: {}, kisOrderNo: {}",
                userId, orderId, result.kisOrderNo());
        sseEmitterManager.send(userId,
                OrderSseEvent.submitted(String.valueOf(order.getId()), stockCode, result.kisOrderNo()));
    }

    /**
     * KIS placeOrder 단일 호출 (재시도 로직은 호출 측에서 처리)
     */
    private KisOrderClient.KisOrderResult callKisPlaceOrder(
            TradeOrderMessage message, KisCredential credential,
            String accessToken, String appKey, String appSecret) {
        OrderSide side       = OrderSide.valueOf(message.side());
        OrderType orderType  = OrderType.valueOf(message.orderType());
        return kisOrderClient.placeOrder(
                accessToken, appKey, appSecret,
                credential.getAccountNo(), credential.getAccountPrdtCd(),
                message.stockCode(), side, orderType,
                message.quantity(), message.limitPrice()
        );
    }

    /**
     * 실패 공통 처리 — Order.reject + SSE ORDER_FAILED
     */
    private void handleFailure(Order order, Long userId, String orderId, String stockCode, ApiException e) {
        String reason = e.getErrorCode().getDefaultMessage();
        order.reject(reason);
        log.warn("[Consumer] 주문 REJECTED - userId: {}, orderId: {}, code: {}, reason: {}",
                userId, orderId, e.getErrorCode().getCode(), reason);
        sseEmitterManager.send(userId,
                OrderSseEvent.failed(String.valueOf(order.getId()), stockCode, reason));
    }
}
