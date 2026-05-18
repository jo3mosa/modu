package com.modu.backend.domain.trading.position.subscription;

import com.modu.backend.domain.market.websocket.KisRealtimeStreamKey;
import com.modu.backend.domain.market.websocket.KisRealtimeStreamType;
import com.modu.backend.domain.market.websocket.KisRealtimeSubscriptionManager;
import com.modu.backend.domain.trading.position.repository.PositionThresholdRepository;
import com.modu.backend.global.config.KisProfiles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 보유 종목 KIS 실시간 시세 자동 구독 (S14P31B106-302)
 *
 * [구독 정책]
 *  - 부팅 시 position_thresholds.is_active=TRUE 인 종목 일괄 구독
 *  - 신규 매수 체결 시 onBuyFilled 호출 → 구독 추가 (호출 책임: 체결 핸들러 291)
 *  - 전량 매도(또는 손절/익절 트리거 후) onFullySold 호출 → 다른 사용자가 보유 중이 아니면 구독 해제
 *
 * [TR 타입]
 *  - 체결가(PRICE/H0STCNT0) 만 구독. 호가는 Position Monitor 비교에 불필요.
 *
 * [구독 한도]
 *  - KIS 플랫폼 계정 1개 동시 구독 약 41건. 초과 시 KIS 가 거부 → UpstreamHandler 에서 WARN 로그
 *  - 다중 계정 풀(P2), 한도 정책(P3) 은 별도 후속 이슈로 분리
 */
@Slf4j
@Component
@Profile(KisProfiles.NOT_GATEWAY)
@RequiredArgsConstructor
public class PositionHoldingSubscriber {

    private final PositionThresholdRepository positionThresholdRepository;
    private final KisRealtimeSubscriptionManager subscriptionManager;

    /**
     * 부팅 시 보유 종목 일괄 구독
     * ApplicationReadyEvent — Spring 컨텍스트가 완전히 준비된 시점에 동작 (lifecycle race 회피)
     */
    @EventListener(ApplicationReadyEvent.class)
    public void subscribeAllOnStartup() {
        Set<String> stockCodes;
        try {
            stockCodes = positionThresholdRepository.findActiveStockCodes();
        } catch (Exception e) {
            log.error("[PositionSubscriber] 부팅 구독 대상 조회 실패", e);
            return;
        }

        if (stockCodes.isEmpty()) {
            log.info("[PositionSubscriber] 부팅 시 활성 보유 종목 없음 — 구독 skip");
            return;
        }

        log.info("[PositionSubscriber] 부팅 일괄 구독 시작 - 종목 수: {}", stockCodes.size());
        int succeeded = 0;
        for (String stockCode : stockCodes) {
            try {
                subscriptionManager.registerServerSide(streamKey(stockCode));
                succeeded++;
            } catch (Exception e) {
                log.warn("[PositionSubscriber] 부팅 구독 실패 - stockCode: {}", stockCode, e);
            }
        }
        log.info("[PositionSubscriber] 부팅 일괄 구독 완료 - 성공: {}/{}", succeeded, stockCodes.size());
    }

    /**
     * 신규 매수 체결 시 구독 추가 — 체결 핸들러(S14P31B106-291) 가 호출
     * 멱등 — 같은 종목 N 번 호출되면 카운트 N. 매도 시 동일 횟수 unregister 필요.
     */
    public void onBuyFilled(String stockCode) {
        if (stockCode == null || stockCode.isBlank()) return;
        try {
            subscriptionManager.registerServerSide(streamKey(stockCode));
            log.info("[PositionSubscriber] 매수 체결 구독 추가 - stockCode: {}", stockCode);
        } catch (Exception e) {
            log.warn("[PositionSubscriber] 매수 체결 구독 추가 실패 - stockCode: {}", stockCode, e);
        }
    }

    /**
     * 전량 매도 시 구독 해제 — 체결 핸들러(291) 또는 Position Monitor 트리거 후 호출
     * 다른 사용자의 활성 임계가 같은 종목에 있으면 카운트만 감소, upstream 은 유지
     */
    public void onFullySold(String stockCode) {
        if (stockCode == null || stockCode.isBlank()) return;
        try {
            subscriptionManager.unregisterServerSide(streamKey(stockCode));
            log.info("[PositionSubscriber] 전량 매도 구독 해제 시도 - stockCode: {}", stockCode);
        } catch (Exception e) {
            log.warn("[PositionSubscriber] 구독 해제 실패 - stockCode: {}", stockCode, e);
        }
    }

    private KisRealtimeStreamKey streamKey(String stockCode) {
        return new KisRealtimeStreamKey(KisRealtimeStreamType.PRICE, stockCode);
    }
}
