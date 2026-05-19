package com.modu.backend.domain.market.feed;

import com.modu.backend.domain.trading.position.repository.PositionThresholdRepository;
import com.modu.backend.domain.user.entity.KisCredential;
import com.modu.backend.domain.user.repository.KisCredentialRepository;
import com.modu.backend.global.config.KisProfiles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * gateway 전용 — 사용자별 {@link UserKisSession} 풀.
 *
 * [생명주기]
 *  - 부팅 시 KIS 자격증명 등록 사용자 전체 세션 생성 + 체결통보 SUBSCRIBE
 *  - 외부에서 subscribePrice/Orderbook/unsubscribe 호출 시 해당 사용자 세션에 위임
 *  - 사용자가 자격증명 변경 시 reload(userId) 로 세션 재생성
 *
 * [부팅 동시성]
 *  사용자 N명 → KIS approval_key 발급 + WS 연결을 순차로 진행 (KIS rate limit 대비).
 *  병렬화는 후속 이슈로 분리. 현재 운영 사용자 수에선 순차로 충분.
 */
@Slf4j
@Component
@Profile(KisProfiles.GATEWAY)
@RequiredArgsConstructor
public class UserKisSessionPool {

    private final KisCredentialRepository credentialRepository;
    private final PositionThresholdRepository positionThresholdRepository;
    private final UserKisSessionFactory factory;

    private final Map<Long, UserKisSession> sessions = new ConcurrentHashMap<>();

    // ── 부팅 자동 생성 ──────────────────────────────────────────────────────

    @EventListener(ApplicationReadyEvent.class)
    public void startAllOnBoot() {
        List<KisCredential> credentials;
        try {
            credentials = credentialRepository.findAll();
        } catch (Exception e) {
            log.error("[UserKisSessionPool] 부팅 자격증명 조회 실패", e);
            return;
        }
        if (credentials.isEmpty()) {
            log.info("[UserKisSessionPool] 부팅 대상 사용자 0명 — skip");
            return;
        }
        log.info("[UserKisSessionPool] 부팅 세션 생성 시작 - 사용자 수: {}", credentials.size());
        int ok = 0, fail = 0;
        for (KisCredential cred : credentials) {
            try {
                openSession(cred.getUserId());
                ok++;
            } catch (Exception e) {
                fail++;
                log.warn("[UserKisSessionPool] 부팅 세션 생성 실패 - userId: {}, error: {}",
                        cred.getUserId(), e.getMessage());
            }
        }
        log.info("[UserKisSessionPool] 부팅 세션 생성 완료 - 성공: {}, 실패: {}", ok, fail);
    }

    // ── 외부 API ────────────────────────────────────────────────────────────

    /** 사용자 세션 가져오기 (없으면 새로 생성). 자격증명 없으면 IllegalStateException. */
    public UserKisSession getOrCreate(long userId) {
        UserKisSession existing = sessions.get(userId);
        if (existing != null) return existing;
        return openSession(userId);
    }

    /** 사용자 세션 명시적 종료. 자격증명 삭제 등의 경우. */
    public void close(long userId) {
        UserKisSession s = sessions.remove(userId);
        if (s != null) {
            s.close();
            log.info("[UserKisSessionPool] session closed - userId: {}", userId);
        }
    }

    /** 사용자 자격증명/HTS ID 변경 시 — 기존 세션 종료 후 재생성. */
    public void reload(long userId) {
        close(userId);
        openSession(userId);
    }

    // ── 내부 — 세션 생성 + 시작 ──────────────────────────────────────────────

    private UserKisSession openSession(long userId) {
        UserKisSession created = factory.create(userId);
        UserKisSession existing = sessions.putIfAbsent(userId, created);
        if (existing != null) {
            // race 가드 — 다른 스레드가 먼저 만들었으면 그쪽 사용, 우리는 폐기
            created.close();
            return existing;
        }
        try {
            created.start();
            autoSubscribeHoldings(userId, created);
            log.info("[UserKisSessionPool] session started - userId: {}", userId);
        } catch (Exception e) {
            sessions.remove(userId, created);
            created.close();
            throw new IllegalStateException("UserKisSession start failed - userId: " + userId, e);
        }
        return created;
    }

    /**
     * 사용자 보유 종목 일괄 자동 구독 — Position Monitor 등 백그라운드 작업이 캐시된 시세를 참조.
     * 실패는 WARN 만 — 세션 자체는 살아있어야 함 (체결통보는 필수, 보유종목 시세는 best-effort).
     */
    private void autoSubscribeHoldings(long userId, UserKisSession session) {
        Set<String> stockCodes;
        try {
            stockCodes = positionThresholdRepository.findActiveStockCodesByUserId(userId);
        } catch (Exception e) {
            log.warn("[UserKisSessionPool] 보유 종목 조회 실패 - userId: {}, error: {}", userId, e.getMessage());
            return;
        }
        if (stockCodes.isEmpty()) return;
        log.info("[UserKisSessionPool] 보유 종목 자동 구독 - userId: {}, count: {}", userId, stockCodes.size());
        for (String code : stockCodes) {
            try {
                session.subscribePrice(code);
            } catch (Exception e) {
                log.warn("[UserKisSessionPool] 보유 종목 구독 실패 - userId: {}, code: {}, error: {}",
                        userId, code, e.getMessage());
            }
        }
    }
}
