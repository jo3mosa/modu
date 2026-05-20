package com.modu.backend.domain.discovery.service;

import com.modu.backend.domain.discovery.repository.DiscoveryRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationReasonBuilderTest {

    private final RecommendationReasonBuilder builder = new RecommendationReasonBuilder();

    @Test
    @DisplayName("tags 1개 → 라벨 + 종목입니다.")
    void singleTag() {
        DiscoveryRow row = row(3);
        assertThat(builder.build(row, List.of("안정성 우수")))
                .isEqualTo("안정성 우수 종목입니다.");
    }

    @Test
    @DisplayName("tags 다수 → ' + ' 로 연결")
    void multipleTags() {
        DiscoveryRow row = row(3);
        assertThat(builder.build(row, List.of("저평가", "고수익")))
                .isEqualTo("저평가 + 고수익 종목입니다.");
    }

    @Test
    @DisplayName("tags 비어있음 → tier 기준 fallback")
    void emptyTagsFallbackByTier() {
        assertThat(builder.build(row(1), List.of())).isEqualTo("안정형 등급에 부합하는 종목입니다.");
        assertThat(builder.build(row(4), List.of())).isEqualTo("적극투자형 등급에 부합하는 종목입니다.");
        assertThat(builder.build(row(5), List.of())).isEqualTo("공격투자형 등급에 부합하는 종목입니다.");
    }

    @Test
    @DisplayName("tags 비어있음 + tier null → 일반 fallback")
    void emptyTagsAndNullTier() {
        DiscoveryRow row = row(null);
        assertThat(builder.build(row, List.of())).isEqualTo("추천 종목입니다.");
    }

    private static DiscoveryRow row(Integer tier) {
        return new DiscoveryRow(
                "005930", "삼성전자", "KOSPI", "IT", tier,
                14.8, 14.2, 0.10,
                null, null, null, null,
                0.026, false,
                76800, java.time.LocalDate.now(), 75400
        );
    }
}
