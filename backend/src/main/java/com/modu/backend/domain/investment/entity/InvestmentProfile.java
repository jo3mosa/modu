package com.modu.backend.domain.investment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 투자 성향 프로필 엔티티 (investment_profiles 테이블)
 *
 * auth 도메인에서는 온보딩 완료 여부 확인(existsByUserId)용으로만 사용
 * 전체 필드는 investment 도메인 구현 시 추가
 */
@Entity
@Table(name = "investment_profiles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InvestmentProfile {

    @Id
    @Column(name = "user_id")
    private Long userId;
}
