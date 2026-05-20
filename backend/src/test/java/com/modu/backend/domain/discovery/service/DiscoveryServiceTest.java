package com.modu.backend.domain.discovery.service;

import com.modu.backend.domain.discovery.dto.DiscoveryResponse;
import com.modu.backend.domain.discovery.dto.TierBlockResponse;
import com.modu.backend.domain.discovery.repository.DiscoveryQueryRepository;
import com.modu.backend.domain.discovery.repository.DiscoveryRow;
import com.modu.backend.domain.investment.entity.InvestmentProfile;
import com.modu.backend.domain.investment.exception.InvestmentErrorCode;
import com.modu.backend.domain.investment.repository.InvestmentProfileRepository;
import com.modu.backend.global.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiscoveryServiceTest {

    @Mock InvestmentProfileRepository investmentProfileRepository;
    @Mock DiscoveryQueryRepository discoveryQueryRepository;

    // 매퍼들은 실제 구현 사용 (단순한 stateless 컴포넌트라 mock 불필요)
    private DiscoveryService service;

    @BeforeEach
    void setUp() {
        service = new DiscoveryService(
                investmentProfileRepository,
                discoveryQueryRepository,
                new RecommendationTagMapper(),
                new RecommendationFilterMatcher(),
                new RecommendationReasonBuilder()
        );
    }

    @Test
    @DisplayName("profile 없음 → PROFILE_NOT_FOUND 예외")
    void profileNotFound() {
        when(investmentProfileRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getRecommendations(1L, 5))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode())
                        .isEqualTo(InvestmentErrorCode.PROFILE_NOT_FOUND));
    }

    @Test
    @DisplayName("정상 — T4 사용자, T1~T4 unlocked + T5 잠금 + tier별 stocks 그룹화")
    void normalFlow_T4User() {
        givenProfileGrade("ACTIVE"); // T4

        // T1 1개 + T3 2개 + T4 1개 = 4개 종목
        when(discoveryQueryRepository.findTopByTierUpToUserGrade(4, 5))
                .thenReturn(List.of(
                        rowAtTier("005930", "삼성전자", 1, "stable", null, null, null),
                        rowAtTier("000660", "SK하이닉스", 3, null, null, "high_growth", null),
                        rowAtTier("035420", "NAVER", 3, null, "high_margin", null, null),
                        rowAtTier("068270", "셀트리온", 4, null, null, null, null)
                ));

        DiscoveryResponse resp = service.getRecommendations(1L, 5);

        // user 등급
        assertThat(resp.user().riskGrade()).isEqualTo("T4");
        assertThat(resp.user().riskLabel()).isEqualTo("적극투자형");

        // 5개 블록 모두 포함
        assertThat(resp.tiers()).hasSize(5);
        Map<String, TierBlockResponse> byTier = mapByKey(resp.tiers());

        assertThat(byTier.get("T1").unlocked()).isTrue();
        assertThat(byTier.get("T1").stocks()).hasSize(1);

        assertThat(byTier.get("T2").unlocked()).isTrue();
        assertThat(byTier.get("T2").stocks()).isEmpty();

        assertThat(byTier.get("T3").unlocked()).isTrue();
        assertThat(byTier.get("T3").stocks()).hasSize(2);

        assertThat(byTier.get("T4").unlocked()).isTrue();
        assertThat(byTier.get("T4").stocks()).hasSize(1);

        // T5 = 사용자 등급 초과 → 잠금 + 빈 배열
        assertThat(byTier.get("T5").unlocked()).isFalse();
        assertThat(byTier.get("T5").stocks()).isEmpty();

        // tierCounts 양식
        assertThat(resp.tierCounts())
                .containsEntry("T1", 1).containsEntry("T2", 0)
                .containsEntry("T3", 2).containsEntry("T4", 1)
                .containsEntry("T5", 0);

        // totalCount
        assertThat(resp.totalCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("알 수 없는 riskGrade → T3 fallback")
    void unknownRiskGradeFallsBackToT3() {
        givenProfileGrade("UNKNOWN_VALUE");
        when(discoveryQueryRepository.findTopByTierUpToUserGrade(anyInt(), anyInt()))
                .thenReturn(List.of());

        DiscoveryResponse resp = service.getRecommendations(1L, 5);

        assertThat(resp.user().riskGrade()).isEqualTo("T3");
        assertThat(resp.user().riskLabel()).isEqualTo("위험중립형");
    }

    @Test
    @DisplayName("종목 매핑 — tags / filters / reason 실데이터 / metrics 변환 / changePct 계산")
    void stockResponseMappingAndChangePct() {
        givenProfileGrade("STABLE"); // T1
        when(discoveryQueryRepository.findTopByTierUpToUserGrade(1, 5))
                .thenReturn(List.of(new DiscoveryRow(
                        "005930", "삼성전자", "KOSPI", "IT", 1,
                        14.0, 14.2, 0.05,
                        "undervalued", "high_margin", "high_growth", "stable",
                        0.026, true,  // atr_ratio (ratio), volume_spike
                        76800, LocalDate.of(2026, 5, 20), 75400
                )));

        DiscoveryResponse resp = service.getRecommendations(1L, 5);
        var stock = resp.tiers().stream()
                .filter(t -> t.tier().equals("T1"))
                .findFirst().orElseThrow()
                .stocks().get(0);

        // tags (status 직역)
        assertThat(stock.tags()).containsExactly("저평가", "고수익", "고성장", "안정성 우수");
        // filters (룰)
        assertThat(stock.filters()).contains("모멘텀", "이슈"); // high_growth + volume_spike
        // reason
        assertThat(stock.reason()).contains("저평가").contains("종목입니다.");
        // metrics — atr_ratio 0.026 → 2.6% 변환
        assertThat(stock.metrics().atr()).isEqualTo(2.6);
        assertThat(stock.metrics().roe()).isEqualTo(14.2);
        assertThat(stock.metrics().per()).isEqualTo(14.0);
        assertThat(stock.metrics().dividendYield()).isNull();
        // changePct = (76800 - 75400) / 75400 * 100 ≈ 1.86
        assertThat(stock.changePct()).isCloseTo(1.86, org.assertj.core.api.Assertions.within(0.01));
        // updatedAt ISO 8601 (KST)
        assertThat(stock.updatedAt()).isNotNull();
    }

    @Test
    @DisplayName("prevClose null → changePct null")
    void changePctNullWhenNoPrevClose() {
        givenProfileGrade("STABLE");
        when(discoveryQueryRepository.findTopByTierUpToUserGrade(1, 5))
                .thenReturn(List.of(new DiscoveryRow(
                        "005930", "삼성전자", "KOSPI", "IT", 1,
                        14.0, 14.2, 0.05,
                        null, null, null, null,
                        null, null,
                        76800, LocalDate.now(), null // prev_close 없음
                )));

        var stock = service.getRecommendations(1L, 5)
                .tiers().stream().filter(t -> t.tier().equals("T1")).findFirst().orElseThrow()
                .stocks().get(0);
        assertThat(stock.changePct()).isNull();
    }

    // ───────────────────────────────────────────────────────────────────
    // helpers
    // ───────────────────────────────────────────────────────────────────

    private void givenProfileGrade(String riskGrade) {
        InvestmentProfile profile = InvestmentProfile.builder()
                .userId(1L)
                .riskScore(50L)
                .riskGrade(riskGrade)
                .answersSnapshot(Map.of())
                .build();
        when(investmentProfileRepository.findById(1L)).thenReturn(Optional.of(profile));
    }

    private DiscoveryRow rowAtTier(String code, String name, int tier,
                                    String stability, String profitability, String growth, String valuation) {
        return new DiscoveryRow(
                code, name, "KOSPI", "IT", tier,
                14.0, 14.2, 0.10,
                valuation, profitability, growth, stability,
                0.026, false,
                76800, LocalDate.now(), 75400
        );
    }

    private Map<String, TierBlockResponse> mapByKey(List<TierBlockResponse> tiers) {
        java.util.LinkedHashMap<String, TierBlockResponse> map = new java.util.LinkedHashMap<>();
        tiers.forEach(t -> map.put(t.tier(), t));
        return map;
    }
}
