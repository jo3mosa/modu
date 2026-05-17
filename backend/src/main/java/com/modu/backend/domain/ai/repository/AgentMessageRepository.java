package com.modu.backend.domain.ai.repository;

import com.modu.backend.domain.ai.entity.AgentMessage;
import com.modu.backend.domain.ai.entity.AgentType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 에이전트 메시지 저장/조회
 *
 * [페이지네이션]
 * 채팅 UX 특성상 실시간 INSERT 가 빈번 → 오프셋은 페이지 밀림 발생.
 * created_at 커서(before) 기반 keyset pagination 으로 안정성 확보.
 *
 * [멱등성]
 * AI 재시도 시 중복 INSERT 차단용 existsBy* 메서드 제공.
 * DB 레벨 partial unique index 가 최종 방어선이고, 본 메서드는 정상 흐름 조기 차단용.
 */
public interface AgentMessageRepository extends JpaRepository<AgentMessage, Long> {

    /**
     * 채널 진입 시 — 최신 N개 메시지 (created_at DESC).
     * 첫 페이지는 before 없이 호출.
     */
    @Query("""
            SELECT m FROM AgentMessage m
            WHERE m.userId = :userId
              AND m.stockCode = :stockCode
            ORDER BY m.createdAt DESC, m.id DESC
            """)
    List<AgentMessage> findRecent(
            @Param("userId") Long userId,
            @Param("stockCode") String stockCode,
            Pageable pageable
    );

    /**
     * 위로 스크롤 — before 이전 메시지 N개 (created_at DESC).
     * id 보조 정렬로 동일 createdAt tie-breaking.
     */
    @Query("""
            SELECT m FROM AgentMessage m
            WHERE m.userId = :userId
              AND m.stockCode = :stockCode
              AND m.createdAt < :before
            ORDER BY m.createdAt DESC, m.id DESC
            """)
    List<AgentMessage> findBefore(
            @Param("userId") Long userId,
            @Param("stockCode") String stockCode,
            @Param("before") OffsetDateTime before,
            Pageable pageable
    );

    /**
     * AI 재시도 멱등 체크 — judgment_id 가 있는 발화 한정.
     * (user_id, judgment_id, agent, seq) 조합은 partial unique index 로도 보호.
     */
    boolean existsByUserIdAndJudgmentIdAndAgentAndSeq(
            Long userId, Long judgmentId, AgentType agent, int seq
    );
}
