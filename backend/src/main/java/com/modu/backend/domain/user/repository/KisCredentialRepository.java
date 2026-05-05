package com.modu.backend.domain.user.repository;

import com.modu.backend.domain.user.entity.KisCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * KIS API 연동 정보 레포지토리
 *
 * user_id가 PK이므로 findById(userId)로 조회 가능
 * 명시적 의도 전달을 위해 findByUserId도 함께 제공
 */
public interface KisCredentialRepository extends JpaRepository<KisCredential, Long> {

    Optional<KisCredential> findByUserId(Long userId);

    boolean existsByUserId(Long userId);

    void deleteByUserId(Long userId);
}
