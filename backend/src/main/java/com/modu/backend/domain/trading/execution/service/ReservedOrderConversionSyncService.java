package com.modu.backend.domain.trading.execution.service;

import com.modu.backend.domain.trading.entity.Order;
import com.modu.backend.domain.trading.entity.OrderStatus;
import com.modu.backend.domain.trading.execution.client.KisReservedOrderInquiryClient;
import com.modu.backend.domain.trading.execution.client.KisReservedOrderInquiryClient.ReservedOrderInquiryResult;
import com.modu.backend.domain.trading.repository.OrderRepository;
import com.modu.backend.domain.user.entity.KisCredential;
import com.modu.backend.domain.user.exception.UserErrorCode;
import com.modu.backend.domain.user.repository.KisCredentialRepository;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.kis.KisApiCallTemplate;
import com.modu.backend.global.util.AesGcmEncryptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 예약주문 → 일반 주문 변환 동기화 서비스 — S14P31B106-291
 *
 * [흐름]
 *  1. orders WHERE status=RESERVED 전체 조회 (user_id 별 그룹)
 *  2. 각 사용자별 KIS order-resv-ccnl 1회 호출 (rate limit 보호 위해 사용자 간 100ms sleep)
 *  3. 응답 rsvn_ord_seq 와 우리 DB orders.kis_rsvn_seq 매칭
 *  4. prcs_rslt 기반 분기:
 *      "처리완료" 또는 odno 있음     → markReservationConverted(odno) (RESERVED → PENDING)
 *      "거부" 또는 rjct_rson2 있음   → order.reject(rjct_rson2)
 *      "미처리" / 미매칭             → skip (다음 사이클 재시도)
 *
 * [실패 격리]
 *  사용자별 try/catch — 한 사용자 KIS 호출 실패가 다른 사용자 처리 차단 안 함.
 *  전체 호출 실패 (전체 try/catch) → ERROR 로그, 다음날 재시도.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservedOrderConversionSyncService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** KIS rate limit 보호용 사용자 간 sleep */
    private static final long PER_USER_SLEEP_MS = 100L;
    /** 조회 기간 — 오늘 기준 N일 이전 발행분까지 포함 (예약주문 길게 걸쳐있는 경우 대비) */
    private static final int LOOKBACK_DAYS = 7;

    private final OrderRepository orderRepository;
    private final KisCredentialRepository kisCredentialRepository;
    private final AesGcmEncryptor encryptor;
    private final KisApiCallTemplate kisApiCallTemplate;
    private final KisReservedOrderInquiryClient inquiryClient;
    /**
     * 명시적 트랜잭션 경계 — 같은 클래스 내부 self-call 시 Spring `@Transactional` 프록시가
     * bypass 되어 order.markReservationConverted / reject 가 비-트랜잭션으로 실행되는 위험 차단.
     */
    private final TransactionTemplate transactionTemplate;

    public void sync() {
        List<Order> reserved = orderRepository.findByStatus(OrderStatus.RESERVED);
        if (reserved.isEmpty()) {
            log.info("[예약주문 변환 동기화] 대상 없음 — 종료");
            return;
        }
        Map<Long, List<Order>> byUser = reserved.stream().collect(Collectors.groupingBy(Order::getUserId));
        log.info("[예약주문 변환 동기화] 시작 - users: {}, totalOrders: {}", byUser.size(), reserved.size());

        for (Map.Entry<Long, List<Order>> entry : byUser.entrySet()) {
            Long userId = entry.getKey();
            List<Order> userOrders = entry.getValue();
            try {
                // 명시적 트랜잭션 — self-call 프록시 bypass 회피
                transactionTemplate.executeWithoutResult(status -> syncOneUser(userId, userOrders));
            } catch (Exception e) {
                log.error("[예약주문 변환 동기화] 사용자 처리 실패 - userId: {}", userId, e);
            }
            sleepBetweenUsers();
        }
    }

    private void syncOneUser(Long userId, List<Order> userOrders) {
        KisCredential credential;
        String appKey;
        String appSecret;
        try {
            credential = kisCredentialRepository.findByUserId(userId)
                    .orElseThrow(() -> new ApiException(UserErrorCode.KIS_NOT_CONNECTED));
            appKey    = encryptor.decrypt(credential.getAppKeyEnc());
            appSecret = encryptor.decrypt(credential.getAppSecretEnc());
        } catch (Exception e) {
            log.warn("[예약주문 변환 동기화] 자격증명 준비 실패 - userId: {}", userId, e);
            return;
        }

        LocalDate today = LocalDate.now(KST);
        String fromDate = today.minusDays(LOOKBACK_DAYS).format(YYYYMMDD);
        String toDate   = today.format(YYYYMMDD);

        List<ReservedOrderInquiryResult> responses;
        try {
            responses = kisApiCallTemplate.callWithTokenRetry(userId, appKey, appSecret,
                    token -> inquiryClient.list(token, appKey, appSecret,
                            credential.getAccountNo(), credential.getAccountPrdtCd(),
                            fromDate, toDate));
        } catch (Exception e) {
            log.warn("[예약주문 변환 동기화] KIS 호출 실패 - userId: {}", userId, e);
            return;
        }

        Map<String, ReservedOrderInquiryResult> bySeq = responses.stream()
                .filter(r -> r.rsvnOrdSeq() != null && !r.rsvnOrdSeq().isBlank())
                .collect(Collectors.toMap(
                        ReservedOrderInquiryResult::rsvnOrdSeq, r -> r, (a, b) -> a));

        for (Order order : userOrders) {
            ReservedOrderInquiryResult match = bySeq.get(order.getKisRsvnSeq());
            if (match == null) {
                log.info("[예약주문 변환 동기화] 매칭 응답 없음 - orderId: {}, rsvnSeq: {}",
                        order.getId(), order.getKisRsvnSeq());
                continue;
            }
            applyConversion(order, match);
        }
    }

    /**
     * 응답 결과에 따라 RESERVED → PENDING 변환 또는 REJECTED 처리.
     */
    private void applyConversion(Order order, ReservedOrderInquiryResult match) {
        String rejectReason = match.rjctRson2();
        boolean rejected = (rejectReason != null && !rejectReason.isBlank())
                || (match.prcsRslt() != null && match.prcsRslt().contains("거부"));
        if (rejected) {
            String reason = "예약주문 변환 거부 - " +
                    (rejectReason != null && !rejectReason.isBlank() ? rejectReason : match.prcsRslt());
            order.reject(reason);
            log.warn("[예약주문 변환 동기화] REJECTED - orderId: {}, reason: {}", order.getId(), reason);
            return;
        }

        String newOrderNo = match.odno();
        if (newOrderNo == null || newOrderNo.isBlank()) {
            // 미처리 상태 — 다음 사이클에 재조회
            log.info("[예약주문 변환 동기화] 미처리 — skip. orderId: {}, prcsRslt: {}",
                    order.getId(), match.prcsRslt());
            return;
        }
        order.markReservationConverted(newOrderNo);
        log.info("[예약주문 변환 동기화] CONVERTED - orderId: {}, kisOrderNo: {}",
                order.getId(), newOrderNo);
    }

    private void sleepBetweenUsers() {
        try {
            Thread.sleep(PER_USER_SLEEP_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
