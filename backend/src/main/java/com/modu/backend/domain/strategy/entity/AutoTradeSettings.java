package com.modu.backend.domain.strategy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 자동매매 설정 엔티티 (auto_trade_settings 테이블)
 *
 * PK = user_id (1:1 매핑). 사용자당 row 1개.
 *
 * [상태 전이]
 *  ACTIVE ⇄ INACTIVE  : 사용자 수동 토글 (PATCH /api/v1/strategies/me/status)
 *  ACTIVE → KILL_SWITCHED : 시스템 발동 (KIS 거부 5회 누적 등)
 *  KILL_SWITCHED → ACTIVE : 사용자 재요청 (동일 API isActive=true)
 *  INACTIVE → KILL_SWITCHED : 발동 불가 (이미 OFF 상태라 거부 카운트 발생 X)
 */
@Entity
@Table(name = "auto_trade_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AutoTradeSettings {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "auto_trade_status", nullable = false, length = 20)
    private AutoTradeStatus autoTradeStatus;

    @Column(name = "kill_switch_reason")
    private String killSwitchReason;

    @Column(name = "kill_switch_triggered_at")
    private OffsetDateTime killSwitchTriggeredAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Builder
    public AutoTradeSettings(Long userId, AutoTradeStatus autoTradeStatus) {
        OffsetDateTime now = OffsetDateTime.now();
        this.userId = userId;
        this.autoTradeStatus = autoTradeStatus != null ? autoTradeStatus : AutoTradeStatus.INACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * 사용자 ON 요청 — INACTIVE / KILL_SWITCHED → ACTIVE
     * Kill Switch 해제도 동일 메서드. reason / triggered_at 초기화.
     */
    public void activate() {
        this.autoTradeStatus = AutoTradeStatus.ACTIVE;
        this.killSwitchReason = null;
        this.killSwitchTriggeredAt = null;
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * 사용자 OFF 요청 — ACTIVE / KILL_SWITCHED → INACTIVE
     * KILL_SWITCHED 상태에서 호출 시 kill switch 메타데이터도 함께 클리어 (사용자 명시 OFF 정책)
     */
    public void inactivate() {
        this.autoTradeStatus = AutoTradeStatus.INACTIVE;
        this.killSwitchReason = null;
        this.killSwitchTriggeredAt = null;
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * 시스템 Kill Switch 발동 — ACTIVE → KILL_SWITCHED
     * KIS 거부 5회 누적 등 자동 트리거 케이스. reason / triggered_at 기록.
     */
    public void triggerKillSwitch(String reason) {
        this.autoTradeStatus = AutoTradeStatus.KILL_SWITCHED;
        this.killSwitchReason = reason;
        this.killSwitchTriggeredAt = OffsetDateTime.now();
        this.updatedAt = this.killSwitchTriggeredAt;
    }
}
