package com.modu.backend.domain.discovery.service;

import com.modu.backend.domain.discovery.repository.DiscoveryRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationFilterMatcherTest {

    private final RecommendationFilterMatcher matcher = new RecommendationFilterMatcher();

    @Test
    @DisplayName("growth=high_growth → 모멘텀")
    void momentumByGrowth() {
        DiscoveryRow row = row(null, null, "high_growth", null, 20.0, null);
        assertThat(matcher.matchFilters(row)).containsExactly("모멘텀");
    }

    @Test
    @DisplayName("volume_spike=true → 모멘텀 + 이슈 (동시 매칭)")
    void momentumAndIssueByVolumeSpike() {
        DiscoveryRow row = row(null, null, null, null, 20.0, true);
        assertThat(matcher.matchFilters(row)).containsExactly("모멘텀", "이슈");
    }

    @Test
    @DisplayName("valuation=undervalued → 가치")
    void valueByValuationStatus() {
        DiscoveryRow row = row("undervalued", null, null, null, 20.0, null);
        assertThat(matcher.matchFilters(row)).containsExactly("가치");
    }

    @Test
    @DisplayName("0 < per < 10 → 가치")
    void valueByLowPer() {
        DiscoveryRow row = row(null, null, null, null, 8.5, null);
        assertThat(matcher.matchFilters(row)).containsExactly("가치");
    }

    @Test
    @DisplayName("per <= 0 또는 per >= 10 또는 null → 가치 X (다른 조건 없으면)")
    void noValueWhenPerOutOfRange() {
        assertThat(matcher.matchFilters(row(null, null, null, null, 15.0, null))).isEmpty();
        assertThat(matcher.matchFilters(row(null, null, null, null, -5.0, null))).isEmpty();
        assertThat(matcher.matchFilters(row(null, null, null, null, null, null))).isEmpty();
    }

    @Test
    @DisplayName("복합 매칭 — high_growth + volume_spike + undervalued + low per → 모두")
    void multipleMatches() {
        DiscoveryRow row = row("undervalued", null, "high_growth", null, 7.0, true);
        assertThat(matcher.matchFilters(row)).containsExactly("모멘텀", "가치", "이슈");
    }

    @Test
    @DisplayName("모두 NULL → 빈 배열")
    void allNullReturnsEmpty() {
        DiscoveryRow row = row(null, null, null, null, null, null);
        assertThat(matcher.matchFilters(row)).isEmpty();
    }

    private static DiscoveryRow row(String valuation, String profitability, String growth, String stability,
                                    Double per, Boolean volumeSpike) {
        return new DiscoveryRow(
                "005930", "삼성전자", "KOSPI", "IT", 3,
                per, 14.2, 0.10,
                valuation, profitability, growth, stability,
                0.026, volumeSpike,
                76800, java.time.LocalDate.now(), 75400
        );
    }
}
