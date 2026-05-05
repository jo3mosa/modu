package com.modu.backend.domain.auth.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.modu.backend.domain.auth.entity.RefreshToken;

/**
 * Refresh Token 레포지토리
 *
 * 토큰 원문이 아닌 해시값으로 조회
 * 쿠키에서 꺼낸 토큰을 SHA-256 해싱 후 findByTokenHash()로 검증
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /** 토큰 해시값으로 Refresh Token을 조회. 재발급/로그아웃 시 사용. */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * 해당 사용자의 모든 Refresh Token을 즉시 삭제
     * @Modifying: 벌크 DELETE SQL을 즉시 실행해 Hibernate 캐시와 DB 간 순서 문제 방지
     */
    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
