package com.modu.backend.domain.investment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

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

    @Column(name = "risk_score", nullable = false)
    private Long riskScore;

    @Column(name = "risk_grade", nullable = false, length = 20)
    private String riskGrade;

    @Column(name = "profile_summary")
    private String profileSummary;

    @Column(name = "investment_goal")
    private String investmentGoal;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answers_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> answersSnapshot;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Builder
    public InvestmentProfile(
            Long userId,
            Long riskScore,
            String riskGrade,
            String profileSummary,
            String investmentGoal,
            Map<String, Object> answersSnapshot,
            Long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        this.userId = userId;
        this.riskScore = riskScore;
        this.riskGrade = riskGrade;
        this.profileSummary = profileSummary;
        this.investmentGoal = investmentGoal;
        this.answersSnapshot = answersSnapshot;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public void update(
            Long riskScore,
            String riskGrade,
            String profileSummary,
            String investmentGoal,
            Map<String, Object> answersSnapshot,
            OffsetDateTime updatedAt
    ) {
        this.riskScore = riskScore;
        this.riskGrade = riskGrade;
        this.profileSummary = profileSummary;
        this.investmentGoal = investmentGoal;
        this.answersSnapshot = answersSnapshot;
        this.updatedAt = updatedAt;
    }
}
