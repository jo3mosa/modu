package com.modu.backend.domain.user.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.modu.backend.domain.user.entity.User;

/**
 * 사용자 레포지토리
 *
 * 소셜 로그인 시 provider + providerId로 기존 가입 여부를 조회
 * 신규 사용자면 save()로 등록, 기존 사용자면 조회 결과를 그대로 사용
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 소셜 provider와 provider 발급 ID로 사용자를 조회
     * 로그인 시 신규/기존 사용자 분기에 사용
     */
    Optional<User> findByProviderAndProviderId(String provider, String providerId);
}
