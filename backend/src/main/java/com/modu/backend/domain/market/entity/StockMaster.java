package com.modu.backend.domain.market.entity;

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

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 종목 마스터 엔티티 (stock_master 테이블)
 *
 * DE 팀의 master_data_loader.py가 매일 08:00 KRX 전 종목 데이터를 수집해 적재
 * - 신규 상장: is_active = true로 추가
 * - 상장 폐지: is_active = false로 비활성화
 *
 * 백엔드는 읽기 전용으로 사용 (쓰기는 Python 스크립트 담당)
 */
@Entity
@Table(name = "stock_master")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockMaster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false, unique = true)
    private String stockCode;

    @Column(name = "stock_name", nullable = false)
    private String stockName;

    /** KOSPI / KOSDAQ */
    @Column(name = "market_type", nullable = false, length = 20)
    private String marketType;

    @Column(name = "sector")
    private String sector;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "listed_at")
    private LocalDate listedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Builder
    public StockMaster(String stockCode, String stockName, String marketType,
                       String sector, boolean isActive) {
        this.stockCode = stockCode;
        this.stockName = stockName;
        this.marketType = marketType;
        this.sector = sector;
        this.isActive = isActive;
    }
}
