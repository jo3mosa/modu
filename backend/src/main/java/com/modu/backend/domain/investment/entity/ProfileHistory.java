package com.modu.backend.domain.investment.entity;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

@Entity
@Table(name = "profile_histories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProfileHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "risk_score", nullable = false)
    private Long riskScore;

    @Column(name = "risk_grade", nullable = false, length = 20)
    private String riskGrade;

    @Column(name = "investment_goal")
    private String investmentGoal;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answers_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> answersSnapshot;

    @Column(name = "version_no", nullable = false)
    private Long versionNo;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Builder
    public ProfileHistory(
            Long userId,
            Long riskScore,
            String riskGrade,
            String investmentGoal,
            Map<String, Object> answersSnapshot,
            Long versionNo,
            OffsetDateTime createdAt
    ) {
        this.userId = userId;
        this.riskScore = riskScore;
        this.riskGrade = riskGrade;
        this.investmentGoal = investmentGoal;
        this.answersSnapshot = answersSnapshot;
        this.versionNo = versionNo;
        this.createdAt = createdAt;
    }
}
