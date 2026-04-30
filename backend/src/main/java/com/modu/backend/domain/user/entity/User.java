package com.modu.backend.domain.user.entity;

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
 * 사용자 엔티티 (users 테이블)
 *
 * 1. 소셜 로그인 전용 사용자 모델
 * - 자체 회원가입 없이 카카오 등 외부 provider를 통해서만 가입
 *
 * 2. provider + providerId 조합으로 소셜 계정을 식별
 * - 동일 사용자가 카카오/구글로 각각 가입하면 별도 계정으로 취급
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소셜 provider가 발급한 사용자 고유 ID (카카오: 숫자 문자열) */
    @Column(name = "provider_id", nullable = false)
    private String providerId;

    /** 소셜 제공자 식별자 (예: "kakao", "google") */
    @Column(name = "provider", nullable = false, length = 20)
    private String provider;

    @Column(name = "nickname", nullable = false)
    private String nickname;

    /** 소셜 동의 항목에서 이메일 제공에 동의한 경우에만 값이 존재 */
    @Column(name = "email")
    private String email;

    @Column(name = "is_news_notify_enabled", nullable = false)
    private boolean isNewsNotifyEnabled = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** soft delete용 컬럼. null이면 활성 사용자. */
    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Builder
    public User(String providerId, String provider, String nickname, String email) {
        this.providerId = providerId;
        this.provider = provider;
        this.nickname = nickname;
        this.email = email;
        this.isNewsNotifyEnabled = true;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
    }
}
