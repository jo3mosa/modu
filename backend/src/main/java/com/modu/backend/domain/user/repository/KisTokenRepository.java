package com.modu.backend.domain.user.repository;

import com.modu.backend.domain.user.entity.KisToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * KIS 토큰 레포지토리
 *
 * 토큰 캐싱 조회 시 findValidToken() 사용
 * - revoke되지 않고 만료 시각이 현재 이후인 가장 최근 토큰 반환
 */
public interface KisTokenRepository extends JpaRepository<KisToken, Long> {

    /** 유효한 KIS 액세스 토큰 조회 (미만료 + 미revoke, 발급일 기준 최신 1건) */
    @Query("SELECT t FROM KisToken t " +
            "WHERE t.userId = :userId " +
            "AND t.tokenType = 'ACCESS_TOKEN' " +
            "AND t.isRevoked = false " +
            "AND t.expiresAt > :now " +
            "ORDER BY t.issuedAt DESC LIMIT 1")
    Optional<KisToken> findValidToken(@Param("userId") Long userId,
                                      @Param("now") OffsetDateTime now);
}
