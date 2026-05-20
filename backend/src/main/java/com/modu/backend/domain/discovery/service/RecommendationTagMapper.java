package com.modu.backend.domain.discovery.service;

import com.modu.backend.domain.discovery.repository.DiscoveryRow;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * status 컬럼 → 한국어 tag 라벨 매핑 — S14P31B106-362
 *
 * DA 분류 결과 (valuation/profitability/growth/stability) 중 "긍정 의미" 만 라벨화.
 * NULL / unknown / 부정 의미는 skip (의미 있는 라벨만 카드에 노출).
 *
 * 매핑 표:
 *   valuation_status     undervalued    → "저평가"
 *   profitability_status high_margin    → "고수익"
 *   growth_status        high_growth    → "고성장"
 *                        steady_growth  → "안정성장"
 *   stability_status     stable         → "안정성 우수"
 *
 * 그 외 (fair / overvalued / normal / low_margin / stagnant / declining / moderate / risky /
 * unknown / NULL) → skip.
 */
@Component
public class RecommendationTagMapper {

    public List<String> mapTags(DiscoveryRow row) {
        List<String> tags = new ArrayList<>();
        if ("undervalued".equals(row.valuationStatus()))    tags.add("저평가");
        if ("high_margin".equals(row.profitabilityStatus())) tags.add("고수익");
        if ("high_growth".equals(row.growthStatus()))        tags.add("고성장");
        else if ("steady_growth".equals(row.growthStatus())) tags.add("안정성장");
        if ("stable".equals(row.stabilityStatus()))          tags.add("안정성 우수");
        return tags;
    }
}
