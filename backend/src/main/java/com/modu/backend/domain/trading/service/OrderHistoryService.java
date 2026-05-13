package com.modu.backend.domain.trading.service;

import com.modu.backend.domain.trading.client.KisOrderHistoryClient;
import com.modu.backend.domain.trading.dto.OrderHistoryResponse;
import com.modu.backend.domain.trading.entity.HistorySourceFilter;
import com.modu.backend.domain.trading.entity.Order;
import com.modu.backend.domain.trading.entity.OrderSource;
import com.modu.backend.domain.trading.exception.OrderErrorCode;
import com.modu.backend.domain.trading.repository.OrderRepository;
import com.modu.backend.domain.user.entity.KisCredential;
import com.modu.backend.domain.user.exception.UserErrorCode;
import com.modu.backend.domain.user.repository.KisCredentialRepository;
import com.modu.backend.domain.user.service.KisTokenService;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.util.AesGcmEncryptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 거래 이력 조회 서비스
 *
 * [처리 흐름]
 * 1. 기간 파라미터 검증 (from/to 기본값, 순서, 1년 제한)
 * 2. KIS 연동 확인 (미연동/모의투자 차단)
 * 3. KIS inquire-daily-ccld 호출 → 기간 내 전체 이력 수집 (연속조회 자동 처리)
 * 4. KIS odno 목록으로 DB orders 일괄 조회 (N+1 방지)
 * 5. KIS 데이터 + DB 메타데이터 병합
 * 6. source 필터 적용 (AUTO/MANUAL 필터 시 DB 미매칭 항목 제외)
 * 7. 최신순 정렬 → page/size 슬라이싱
 *
 * [트랜잭션]
 * 읽기 전용 조회이므로 @Transactional 미사용. DB 커넥션 점유 최소화.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderHistoryService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /** 생략 시 기본 조회 기간 — 최근 3개월 */
    private static final int DEFAULT_PERIOD_MONTHS = 3;
    /** 최대 조회 기간 — 1년 */
    private static final int MAX_PERIOD_MONTHS    = 12;

    /**
     * 자동 매매로 분류되는 OrderSource 집합 — HistorySourceFilter.AUTO 매칭 시 사용
     * 새 OrderSource 추가 시 자동 매매 여부를 의식적으로 결정하도록 화이트리스트 방식 사용
     */
    private static final Set<String> AUTO_SOURCES = Set.of(
            OrderSource.AI_DECISION.name(),
            OrderSource.STOP_LOSS.name(),
            OrderSource.TAKE_PROFIT.name()
    );

    private final OrderRepository orderRepository;
    private final KisCredentialRepository kisCredentialRepository;
    private final KisTokenService kisTokenService;
    private final KisOrderHistoryClient kisOrderHistoryClient;
    private final AesGcmEncryptor encryptor;

    public OrderHistoryResponse getOrderHistory(Long userId,
                                                HistorySourceFilter source,
                                                LocalDate from,
                                                LocalDate to,
                                                int page,
                                                int size) {
        // 1. 기간 정규화 및 검증
        LocalDate today      = LocalDate.now(KST);
        LocalDate resolvedTo   = (to   != null) ? to   : today;
        LocalDate resolvedFrom = (from != null) ? from : resolvedTo.minusMonths(DEFAULT_PERIOD_MONTHS);
        validatePeriod(resolvedFrom, resolvedTo);

        HistorySourceFilter resolvedSource = (source != null) ? source : HistorySourceFilter.ALL;
        int resolvedPage = page < 1 ? 1 : page;
        int resolvedSize = size < 1 ? 20 : size;

        // 2. KIS 연동 확인
        KisCredential credential = kisCredentialRepository.findByUserId(userId)
                .orElseThrow(() -> new ApiException(UserErrorCode.KIS_NOT_CONNECTED));
        if (!credential.isRealAccount()) {
            throw new ApiException(UserErrorCode.KIS_MOCK_ACCOUNT_NOT_SUPPORTED);
        }

        String appKey      = encryptor.decrypt(credential.getAppKeyEnc());
        String appSecret   = encryptor.decrypt(credential.getAppSecretEnc());
        String accessToken = kisTokenService.getOrIssueAccessToken(userId, appKey, appSecret);

        // 3. KIS 거래 이력 조회 (연속조회 자동 처리)
        List<KisOrderHistoryClient.KisHistoryItem> kisItems = kisOrderHistoryClient.getOrderHistory(
                accessToken, appKey, appSecret,
                credential.getAccountNo(), credential.getAccountPrdtCd(),
                resolvedFrom, resolvedTo
        );

        if (kisItems.isEmpty()) {
            return new OrderHistoryResponse(Collections.emptyList(), 0, resolvedPage, resolvedSize);
        }

        // 4. KIS odno 목록으로 DB 주문 일괄 조회 → Map<odno, Order>
        List<String> odnoList = kisItems.stream()
                .map(KisOrderHistoryClient.KisHistoryItem::odno)
                .filter(o -> o != null && !o.isBlank())
                .toList();

        Map<String, Order> orderByOdno = odnoList.isEmpty()
                ? Collections.emptyMap()
                : orderRepository.findByUserIdAndKisOrderNoIn(userId, odnoList).stream()
                    .collect(Collectors.toMap(Order::getKisOrderNo, o -> o));

        // 5. KIS + DB 병합 → 6. source 필터 → 7. 정렬/페이지네이션
        List<OrderHistoryResponse.OrderHistoryItem> merged = kisItems.stream()
                .map(kisItem -> buildItem(kisItem, orderByOdno.get(kisItem.odno())))
                .filter(item -> matchesSource(item, resolvedSource))
                // OffsetDateTime 기준 시간 정렬 — 문자열 정렬은 오프셋 차이/포맷 변경에 취약
                .sorted(Comparator.comparing(
                        (OrderHistoryResponse.OrderHistoryItem i) -> toSortKey(i.createdAt()),
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        int totalCount = merged.size();
        // 큰 page/size 입력 시 int overflow 로 음수 인덱스 발생 방지 — long 계산 후 totalCount 로 clamp
        long offset    = (long) (resolvedPage - 1) * (long) resolvedSize;
        int fromIdx    = (int) Math.min(Math.max(offset, 0L), totalCount);
        int toIdx      = (int) Math.min((long) fromIdx + resolvedSize, totalCount);
        List<OrderHistoryResponse.OrderHistoryItem> pageItems = merged.subList(fromIdx, toIdx);

        return new OrderHistoryResponse(pageItems, totalCount, resolvedPage, resolvedSize);
    }

    /**
     * 정렬용 시간 키 변환 — createdAt(ISO_OFFSET_DATE_TIME 문자열) → OffsetDateTime
     * null/파싱 실패는 null 반환 (정렬에서 nullsLast 적용)
     */
    private OffsetDateTime toSortKey(String createdAt) {
        if (createdAt == null) return null;
        try {
            return OffsetDateTime.parse(createdAt, ISO);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 기간 검증 — from > to 거부, 1년 초과 거부
     */
    private void validatePeriod(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new ApiException(OrderErrorCode.HISTORY_INVALID_DATE_RANGE);
        }
        if (from.isBefore(to.minusMonths(MAX_PERIOD_MONTHS))) {
            throw new ApiException(OrderErrorCode.HISTORY_PERIOD_TOO_LONG);
        }
    }

    /**
     * KIS 항목 + DB 주문 → OrderHistoryItem 변환
     * DB 매칭 시 orderId/source 채우고 createdAt 은 orders.created_at 사용.
     * 미매칭 시 KIS orderedAt(KST → OffsetDateTime)을 createdAt 으로 사용.
     */
    private OrderHistoryResponse.OrderHistoryItem buildItem(
            KisOrderHistoryClient.KisHistoryItem kisItem, Order order) {

        String orderId  = (order != null) ? String.valueOf(order.getId()) : null;
        String source   = (order != null) ? order.getSource().name() : null;
        String createdAt = (order != null)
                ? order.getCreatedAt().format(ISO)
                : formatKisOrderedAt(kisItem.orderedAt());

        return new OrderHistoryResponse.OrderHistoryItem(
                orderId,
                kisItem.stockCode(),
                kisItem.stockName(),
                kisItem.side(),
                kisItem.orderType(),
                kisItem.quantity(),
                kisItem.price(),
                kisItem.status(),
                source,
                createdAt
        );
    }

    /**
     * KIS ord_dt+ord_tmd(LocalDateTime, KST 기준) → ISO_OFFSET_DATE_TIME 문자열
     * null 이면 null 반환
     */
    private String formatKisOrderedAt(LocalDateTime orderedAt) {
        if (orderedAt == null) return null;
        OffsetDateTime odt = orderedAt.atZone(KST).toOffsetDateTime();
        return odt.format(ISO);
    }

    /**
     * source 필터 매칭
     *  ALL    → 모두 통과
     *  AUTO   → source ∈ AUTO_SOURCES (AI_DECISION / STOP_LOSS / TAKE_PROFIT)
     *  MANUAL → source == MANUAL
     * (AUTO/MANUAL 필터 시 DB 미매칭 항목 source=null 은 제외)
     */
    private boolean matchesSource(OrderHistoryResponse.OrderHistoryItem item,
                                  HistorySourceFilter filter) {
        if (filter == HistorySourceFilter.ALL) return true;
        if (item.source() == null) return false;
        return filter == HistorySourceFilter.AUTO
                ? AUTO_SOURCES.contains(item.source())
                : OrderSource.MANUAL.name().equals(item.source());
    }
}
