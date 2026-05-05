package com.modu.backend.domain.user.entity;

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

import java.time.OffsetDateTime;

/**
 * KIS OAuth 액세스 토큰 엔티티 (kis_tokens_log 테이블)
 *
 * KIS API 호출에 필요한 Bearer 토큰을 DB에 캐싱
 * - 유효기간: 1일 (KIS 정책)
 * - 만료 전까지 재사용, 만료 시 KisTokenClient로 재발급
 *
 * KisCredential(영구 자격증명)과 구분
 * - KisCredential: appKey/appSecret 저장 (열쇠)
 * - KisToken: 발급된 Bearer 토큰 캐싱 (출입증)
 */
@Entity
@Table(name = "kis_tokens_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KisToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 토큰 종류 (현재: ACCESS_TOKEN, 추후: WEBSOCKET_KEY 등 확장 가능) */
    @Column(name = "token_type", nullable = false, length = 20)
    private String tokenType;

    @Column(name = "access_token", nullable = false)
    private String accessToken;

    @Column(name = "issued_at", nullable = false)
    private OffsetDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "is_revoked", nullable = false)
    private boolean isRevoked = false;

    @Builder
    public KisToken(Long userId, String tokenType, String accessToken,
                    OffsetDateTime issuedAt, OffsetDateTime expiresAt) {
        this.userId = userId;
        this.tokenType = tokenType;
        this.accessToken = accessToken;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.isRevoked = false;
    }
}
