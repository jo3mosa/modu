package com.modu.backend.domain.user.repository;

import com.modu.backend.domain.user.entity.KisToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * KIS 토큰 레포지토리
 *
 * findValidToken(): revoke되지 않고 만료 시각이 현재 이후인 가장 최근 토큰 반환
 * Spring Data 파생 쿼리 사용 (JPQL LIMIT은 Hibernate 전용 확장이라 표준 방식으로 대체)
 */
public interface KisTokenRepository extends JpaRepository<KisToken, Long> {

    /** 유효한 KIS 토큰 조회 (미만료 + 미revoke, 발급일 기준 최신 1건) */
    Optional<KisToken> findFirstByUserIdAndTokenTypeAndIsRevokedFalseAndExpiresAtAfterOrderByIssuedAtDesc(
            Long userId, String tokenType, OffsetDateTime now);

    /** appKey 변경 시 기존 토큰 전체 삭제 (@Modifying: 즉시 DELETE SQL 실행) */
    @Modifying
    @Query("DELETE FROM KisToken t WHERE t.userId = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
