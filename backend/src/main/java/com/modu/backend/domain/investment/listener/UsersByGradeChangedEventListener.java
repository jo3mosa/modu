package com.modu.backend.domain.investment.listener;

import com.modu.backend.domain.investment.event.UsersByGradeChangedEvent;
import com.modu.backend.domain.investment.repository.UsersByGradeRedisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * users:by_grade Redis Set 갱신 이벤트 리스너 — S14P31B106-357
 *
 * StrategyProfileService.updateProfile() 의 DB 트랜잭션이 commit 된 뒤 호출.
 *
 * [동작]
 *  - 신규 profile (prevGradeInt = null): SADD new
 *  - 등급 동일: skip (불필요한 Redis 호출 방지)
 *  - 등급 변경: SREM prev + SADD new
 *
 * [실패 처리]
 *  Repository 가 throw X (ERROR 로그만) — 본 리스너도 별도 try-catch 불요.
 *  AFTER_COMMIT 이라 예외 propagation 이 DB 트랜잭션을 되돌리지 않지만,
 *  defensive 차원에서 추가 catch 로 다른 이벤트 처리에 영향 없도록 격리.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UsersByGradeChangedEventListener {

    private final UsersByGradeRedisRepository usersByGradeRedisRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(UsersByGradeChangedEvent event) {
        if (event.prevGradeInt() != null && event.prevGradeInt() == event.newGradeInt()) {
            return;
        }

        // remove 와 add 를 독립 try-catch 로 분리 — 한쪽 실패가 다른 쪽 시도를 막지 않도록 (best-effort).
        if (event.prevGradeInt() != null) {
            try {
                usersByGradeRedisRepository.removeUser(event.userId(), event.prevGradeInt());
            } catch (Exception e) {
                log.error("[UsersByGradeChanged] SREM 실패 - userId: {}, prev: {}",
                        event.userId(), event.prevGradeInt(), e);
            }
        }
        try {
            usersByGradeRedisRepository.addUser(event.userId(), event.newGradeInt());
        } catch (Exception e) {
            log.error("[UsersByGradeChanged] SADD 실패 - userId: {}, new: {}",
                    event.userId(), event.newGradeInt(), e);
        }
    }
}
