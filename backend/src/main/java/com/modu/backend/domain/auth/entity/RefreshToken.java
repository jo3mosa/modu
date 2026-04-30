package com.modu.backend.domain.auth.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Refresh Token 엔티티 (refresh_tokens 테이블)
 *
 * 1. 토큰 원문 대신 SHA-256 해시값(token_hash)만 저장
 * - DB가 탈취되더라도 원본 토큰을 복원할 수 없도록 하기 위함
 *
 * 2. 로그아웃 또는 토큰 재발급 시 revoke()를 호출해 무효화
 * - 만료 또는 revoke 여부는 isExpired(), isRevoked()로 확인
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Refresh Token 원문의 SHA-256 해시값 */
    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private OffsetDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    /** null이면 유효한 토큰, 값이 있으면 무효화된 토큰 */
    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Builder
    public RefreshToken(Long userId, String tokenHash, OffsetDateTime issuedAt, OffsetDateTime expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    /** 로그아웃 또는 재발급 시 기존 토큰을 무효화 */
    public void revoke() {
        this.revokedAt = OffsetDateTime.now();
    }

    public boolean isRevoked() {
        return this.revokedAt != null;
    }

    public boolean isExpired() {
        return OffsetDateTime.now().isAfter(this.expiresAt);
    }
}
