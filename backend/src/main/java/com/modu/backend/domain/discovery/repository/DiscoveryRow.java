package com.modu.backend.domain.discovery.repository;

import java.time.LocalDate;

/**
 * Discovery 조회 SQL 의 row projection — S14P31B106-362
 *
 * Service 가 본 record 를 받아 응답 DTO 로 가공 (tags / filters / reason / changePct 계산).
 *
 * 모든 number / boolean 은 Wrapper 타입 — DB NULL 허용 (LEFT JOIN 대상 컬럼들).
 */
public record DiscoveryRow(
        String stockCode,
        String stockName,
        String marketType,
        String sector,
        Integer riskTier,
        Double per,
        Double roe,
        Double roeRankPct,
        String valuationStatus,
        String profitabilityStatus,
        String growthStatus,
        String stabilityStatus,
        Double atrRatio,
        Boolean volumeSpike,
        Integer price,
        LocalDate priceDate,
        Integer prevClose
) {}
