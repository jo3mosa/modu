package com.modu.backend.domain.trading.entity;

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
@Table(name = "trading_rule_histories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TradingRuleHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "stop_loss_pct", nullable = false)
    private Long stopLossPct;

    @Column(name = "take_profit_pct", nullable = false)
    private Long takeProfitPct;

    @Column(name = "max_daily_order_count", nullable = false)
    private Long maxDailyOrderCount;

    @Column(name = "daily_loss_limit_amount", nullable = false)
    private Long dailyLossLimitAmount;

    @Column(name = "ai_budget_amount", nullable = false)
    private Long aiBudgetAmount;

    @Column(name = "natural_language_rule")
    private String naturalLanguageRule;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parsed_rule_json", columnDefinition = "jsonb")
    private Map<String, Object> parsedRuleJson;

    @Column(name = "version_no", nullable = false)
    private Long versionNo;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Builder
    public TradingRuleHistory(
            Long userId,
            Long stopLossPct,
            Long takeProfitPct,
            Long maxDailyOrderCount,
            Long dailyLossLimitAmount,
            Long aiBudgetAmount,
            String naturalLanguageRule,
            Map<String, Object> parsedRuleJson,
            Long versionNo,
            OffsetDateTime createdAt
    ) {
        this.userId = userId;
        this.stopLossPct = stopLossPct;
        this.takeProfitPct = takeProfitPct;
        this.maxDailyOrderCount = maxDailyOrderCount;
        this.dailyLossLimitAmount = dailyLossLimitAmount;
        this.aiBudgetAmount = aiBudgetAmount;
        this.naturalLanguageRule = naturalLanguageRule;
        this.parsedRuleJson = parsedRuleJson;
        this.versionNo = versionNo;
        this.createdAt = createdAt;
    }
}
