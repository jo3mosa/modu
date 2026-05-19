package com.modu.backend.domain.trading.entity;

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
 * User trading risk rule entity mapped to trading_rules.
 */
@Entity
@Table(name = "trading_rules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TradingRule {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "stop_loss_pct", nullable = false)
    private Long stopLossPct;

    @Column(name = "take_profit_pct", nullable = false)
    private Long takeProfitPct;

    @Column(name = "max_daily_order_count", nullable = false)
    private Long maxDailyOrderCount;

    @Column(name = "daily_loss_limit_amount", nullable = false)
    private Long dailyLossLimitAmount;

    // AI 위임 운용 자금 한도 (KRW). 0 = 미설정 → risk_gate / SignalHandler 모두 검증 skip.
    @Column(name = "ai_budget_amount", nullable = false)
    private Long aiBudgetAmount;

    @Column(name = "natural_language_rule")
    private String naturalLanguageRule;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parsed_rule_json", columnDefinition = "jsonb")
    private Map<String, Object> parsedRuleJson;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Builder
    public TradingRule(
            Long userId,
            Long stopLossPct,
            Long takeProfitPct,
            Long maxDailyOrderCount,
            Long dailyLossLimitAmount,
            Long aiBudgetAmount,
            String naturalLanguageRule,
            Map<String, Object> parsedRuleJson,
            Long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        this.userId = userId;
        this.stopLossPct = stopLossPct;
        this.takeProfitPct = takeProfitPct;
        this.maxDailyOrderCount = maxDailyOrderCount;
        this.dailyLossLimitAmount = dailyLossLimitAmount;
        this.aiBudgetAmount = aiBudgetAmount;
        this.naturalLanguageRule = naturalLanguageRule;
        this.parsedRuleJson = parsedRuleJson;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public void update(
            Long stopLossPct,
            Long takeProfitPct,
            Long maxDailyOrderCount,
            Long dailyLossLimitAmount,
            Long aiBudgetAmount,
            OffsetDateTime updatedAt
    ) {
        this.stopLossPct = stopLossPct;
        this.takeProfitPct = takeProfitPct;
        this.maxDailyOrderCount = maxDailyOrderCount;
        this.dailyLossLimitAmount = dailyLossLimitAmount;
        this.aiBudgetAmount = aiBudgetAmount;
        this.updatedAt = updatedAt;
    }
}
