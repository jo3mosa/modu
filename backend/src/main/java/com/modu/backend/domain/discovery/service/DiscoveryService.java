package com.modu.backend.domain.discovery.service;

import com.modu.backend.domain.discovery.dto.DiscoveryResponse;
import com.modu.backend.domain.discovery.dto.DiscoveryUserResponse;
import com.modu.backend.domain.discovery.dto.StockMetricsResponse;
import com.modu.backend.domain.discovery.dto.StockRecommendationResponse;
import com.modu.backend.domain.discovery.dto.TierBlockResponse;
import com.modu.backend.domain.discovery.enums.TierDescription;
import com.modu.backend.domain.discovery.repository.DiscoveryQueryRepository;
import com.modu.backend.domain.discovery.repository.DiscoveryRow;
import com.modu.backend.domain.investment.entity.InvestmentProfile;
import com.modu.backend.domain.investment.exception.InvestmentErrorCode;
import com.modu.backend.domain.investment.repository.InvestmentProfileRepository;
import com.modu.backend.domain.strategy.dto.InvestmentRiskLevel;
import com.modu.backend.global.error.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 종목 추천 (Discovery) 서비스 — S14P31B106-362
 *
 * [흐름]
 *  1. 사용자 risk_grade 조회 (InvestmentProfile)
 *  2. DiscoveryQueryRepository 호출 (사용자 등급 이하 tier 만, tier 당 perTier 개)
 *  3. tier 별 그룹화 + 응답 DTO 빌드 (모든 T1~T5 블록 포함, 초과 tier 는 unlocked=false)
 *
 * [tags / filters / reason] — 3단계에서 매퍼 주입 예정. 현재는 빈 / null placeholder.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiscoveryService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final InvestmentProfileRepository investmentProfileRepository;
    private final DiscoveryQueryRepository discoveryQueryRepository;
    private final RecommendationTagMapper tagMapper;
    private final RecommendationFilterMatcher filterMatcher;
    private final RecommendationReasonBuilder reasonBuilder;

    @Transactional(readOnly = true)
    public DiscoveryResponse getRecommendations(Long userId, int perTier) {
        // 1. 사용자 등급 조회
        InvestmentProfile profile = investmentProfileRepository.findById(userId)
                .orElseThrow(() -> new ApiException(InvestmentErrorCode.PROFILE_NOT_FOUND));

        InvestmentRiskLevel level = resolveRiskLevel(profile.getRiskGrade());
        int userMaxTier = level.toGradeInt();

        // 2. 추천 종목 조회 (tier 별 상위 N)
        List<DiscoveryRow> rows = discoveryQueryRepository.findTopByTierUpToUserGrade(userMaxTier, perTier);

        // 3. tier 별 그룹화
        Map<Integer, List<StockRecommendationResponse>> grouped = groupByTier(rows);

        // 4. T1~T5 블록 빌드 (사용자 등급 이하만 unlocked=true)
        List<TierBlockResponse> tiers = buildTierBlocks(grouped, userMaxTier);

        // 5. tierCounts (T1~T5 모두 키 포함)
        Map<String, Integer> tierCounts = buildTierCounts(tiers);

        int totalCount = tiers.stream().mapToInt(t -> t.stocks().size()).sum();

        return new DiscoveryResponse(
                new DiscoveryUserResponse(
                        "T" + userMaxTier,
                        level.getLabel(),
                        profile.getUpdatedAt()
                ),
                OffsetDateTime.now(KST),
                totalCount,
                tierCounts,
                tiers
        );
    }

    // ───────────────────────────────────────────────────────────────────
    // 내부 매핑
    // ───────────────────────────────────────────────────────────────────

    private InvestmentRiskLevel resolveRiskLevel(String riskGrade) {
        try {
            return InvestmentRiskLevel.valueOf(riskGrade);
        } catch (IllegalArgumentException | NullPointerException e) {
            log.error("[Discovery] 알 수 없는 riskGrade - value: '{}'", riskGrade);
            // 보수적으로 T3 (위험중립형) 으로 fallback
            return InvestmentRiskLevel.RISK_NEUTRAL;
        }
    }

    private Map<Integer, List<StockRecommendationResponse>> groupByTier(List<DiscoveryRow> rows) {
        Map<Integer, List<StockRecommendationResponse>> grouped = new LinkedHashMap<>();
        for (DiscoveryRow row : rows) {
            grouped.computeIfAbsent(row.riskTier(), k -> new ArrayList<>())
                    .add(toStockResponse(row));
        }
        return grouped;
    }

    /**
     * DiscoveryRow → StockRecommendationResponse 매핑.
     * tags / filters / reason 은 3단계에서 매퍼 주입 예정 — 현재 빈 / null placeholder.
     */
    private StockRecommendationResponse toStockResponse(DiscoveryRow row) {
        OffsetDateTime updatedAt = row.priceDate() != null
                ? row.priceDate().atStartOfDay(KST).toOffsetDateTime()
                : null;

        List<String> tags = tagMapper.mapTags(row);
        List<String> filters = filterMatcher.matchFilters(row);
        String reason = reasonBuilder.build(row, tags);

        return new StockRecommendationResponse(
                row.stockCode(),
                row.stockName(),
                row.marketType(),
                row.sector(),
                row.price(),
                computeChangePct(row.price(), row.prevClose()),
                reason,
                tags,
                new StockMetricsResponse(
                        row.atrRatio() != null ? row.atrRatio() * 100.0 : null, // ratio → %
                        row.roe(),
                        row.per(),
                        null  // dividendYield — 데이터 부재
                ),
                filters,
                updatedAt
        );
    }

    private Double computeChangePct(Integer price, Integer prevClose) {
        if (price == null || prevClose == null || prevClose == 0) return null;
        double pct = ((double) (price - prevClose) / prevClose) * 100.0;
        return Math.round(pct * 100.0) / 100.0;
    }

    private List<TierBlockResponse> buildTierBlocks(
            Map<Integer, List<StockRecommendationResponse>> grouped, int userMaxTier) {
        List<TierBlockResponse> blocks = new ArrayList<>(5);
        for (TierDescription tier : TierDescription.values()) {
            boolean unlocked = tier.getTierNumber() <= userMaxTier;
            List<StockRecommendationResponse> stocks = unlocked
                    ? grouped.getOrDefault(tier.getTierNumber(), List.of())
                    : List.of();
            blocks.add(new TierBlockResponse(
                    tier.getTierKey(),
                    tier.getLabel(),
                    tier.getDescription(),
                    unlocked,
                    stocks
            ));
        }
        return blocks;
    }

    private Map<String, Integer> buildTierCounts(List<TierBlockResponse> tiers) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (TierBlockResponse t : tiers) {
            counts.put(t.tier(), t.stocks().size());
        }
        return counts;
    }
}
