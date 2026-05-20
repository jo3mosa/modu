package com.modu.backend.domain.discovery.service;

import com.modu.backend.domain.discovery.repository.DiscoveryRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationTagMapperTest {

    private final RecommendationTagMapper mapper = new RecommendationTagMapper();

    @Test
    @DisplayName("긍정 status 4종 모두 매핑")
    void mapsAllPositiveStatuses() {
        DiscoveryRow row = rowWithStatuses("undervalued", "high_margin", "high_growth", "stable");
        List<String> tags = mapper.mapTags(row);
        assertThat(tags).containsExactly("저평가", "고수익", "고성장", "안정성 우수");
    }

    @Test
    @DisplayName("growth=steady_growth → 안정성장 (high_growth 와 배타)")
    void mapsSteadyGrowth() {
        DiscoveryRow row = rowWithStatuses(null, null, "steady_growth", null);
        assertThat(mapper.mapTags(row)).containsExactly("안정성장");
    }

    @Test
    @DisplayName("부정/중립/NULL 은 모두 skip")
    void skipsNeutralAndNegative() {
        DiscoveryRow row = rowWithStatuses("overvalued", "low_margin", "declining", "risky");
        assertThat(mapper.mapTags(row)).isEmpty();

        DiscoveryRow nulls = rowWithStatuses(null, null, null, null);
        assertThat(mapper.mapTags(nulls)).isEmpty();
    }

    private static DiscoveryRow rowWithStatuses(String valuation, String profitability, String growth, String stability) {
        return new DiscoveryRow(
                "005930", "삼성전자", "KOSPI", "IT", 3,
                14.8, 14.2, 0.10,
                valuation, profitability, growth, stability,
                0.026, false,
                76800, java.time.LocalDate.now(), 75400
        );
    }
}
