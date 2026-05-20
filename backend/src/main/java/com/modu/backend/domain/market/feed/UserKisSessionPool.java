package com.modu.backend.domain.market.feed;

import com.modu.backend.domain.trading.position.repository.PositionThresholdRepository;
import com.modu.backend.domain.user.entity.KisCredential;
import com.modu.backend.domain.user.repository.KisCredentialRepository;
import com.modu.backend.domain.user.service.KisTokenService;
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

    /** invalid approval 복구 쿨다운 (per-user) — 새 키도 거부될 때 무한 재발급 storm 방지. */
    private static final long RECOVERY_COOLDOWN_MS = 60_000;

    private final KisCredentialRepository credentialRepository;
    private final PositionThresholdRepository positionThresholdRepository;
    private final UserKisSessionFactory factory;
    private final KisTokenService kisTokenService;

    private final Map<Long, UserKisSession> sessions = new ConcurrentHashMap<>();
    /** userId → 마지막 invalid approval 복구 시각(ms). 쿨다운 판정용. */
    private final Map<Long, Long> lastRecoveryAt = new ConcurrentHashMap<>();

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

    /**
     * KIS "invalid approval" 거부 복구 — 무효 승인키 evict 후 새 키로 세션 재생성.
     * UserKisSession 이 거부를 감지하면 콜백으로 호출한다.
     *
     * 60초 쿨다운: 재발급해도 또 거부되는 경우(실시간 권한 미신청 / appkey 충돌)엔
     * 1분에 한 번만 시도하고 멈춰, 과거의 1초 reconnect storm 재발을 차단한다.
     * (세션은 recoveryTriggered 로 재연결을 멈추므로, 쿨다운에 막히면 조용히 정지)
     */
    public void recoverInvalidApproval(long userId) {
        long now = System.currentTimeMillis();
        Long last = lastRecoveryAt.get(userId);
        if (last != null && now - last < RECOVERY_COOLDOWN_MS) {
            log.warn("[UserKisSessionPool] invalid approval 복구 쿨다운 — 재시도 보류. userId: {} (권한/충돌 확인 필요)", userId);
            return;
        }
        lastRecoveryAt.put(userId, now);
        log.warn("[UserKisSessionPool] invalid approval 복구 — 승인키 evict + 세션 재생성. userId: {}", userId);
        kisTokenService.evictWebSocketKey(userId);
        // reload 는 KIS 연결/구독 I/O 를 포함 → 호출 스레드(WS 핸들러) 블로킹 방지 위해 가상 스레드로 분리.
        Thread.ofVirtual().start(() -> {
            try {
                reload(userId);
            } catch (Exception e) {
                log.error("[UserKisSessionPool] invalid approval 복구 reload 실패 - userId: {}", userId, e);
            }
        });
    }

    // ── 내부 — 세션 생성 + 시작 ──────────────────────────────────────────────

    /**
     * 세션 생성 + 시작 + 보유종목 자동 구독을 한 단위로 atomic 수행.
     * computeIfAbsent — 같은 userId 에 대한 동시 호출은 lock 안에서 직렬화되며,
     * start() 실패 시 map 에 등록되지 않아 다음 호출에서 재시도된다.
     * (PR #166 review #3 — start 전 노출되어 미시작 세션을 반환하던 race 해소)
     */
    private UserKisSession openSession(long userId) {
        return sessions.computeIfAbsent(userId, id -> {
            UserKisSession created = factory.create(id, () -> recoverInvalidApproval(id));
            try {
                created.start();
                autoSubscribeHoldings(id, created);
                log.info("[UserKisSessionPool] session started - userId: {}", id);
                return created;
            } catch (Exception e) {
                created.close();
                throw new IllegalStateException("UserKisSession start failed - userId: " + id, e);
            }
        });
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
