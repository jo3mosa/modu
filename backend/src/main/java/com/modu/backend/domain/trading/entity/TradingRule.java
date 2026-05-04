package com.modu.backend.domain.trading.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자 매매 룰셋 엔티티 (trading_rules 테이블)
 *
 * auth 도메인에서는 룰셋 설정 완료 여부 확인(existsByUserId)용으로만 사용
 * 전체 필드는 trading 도메인 구현 시 추가
 */
@Entity
@Table(name = "trading_rules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TradingRule {

    @Id
    @Column(name = "user_id")
    private Long userId;
}
