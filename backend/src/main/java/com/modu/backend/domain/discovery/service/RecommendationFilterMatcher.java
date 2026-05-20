package com.modu.backend.domain.discovery.service;

import com.modu.backend.domain.discovery.repository.DiscoveryRow;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 종목별 filter chip 카테고리 매칭 — S14P31B106-362
 *
 * FE DiscoveryPage 의 상단 chip (모멘텀 / 가치 / 이슈) 사용자 선택 시
 * stocks[].filters 배열로 클라이언트 사이드 필터링.
 *
 * 매칭 룰:
 *   모멘텀 : growth_status='high_growth' OR volume_spike=true
 *   가치   : valuation_status='undervalued' OR (per != null AND 0 < per < 10)
 *   이슈   : volume_spike=true
 *
 * 배당은 dividendYield 데이터 부재로 미지원 (FE chip 도 제거 예정).
 *
 * 다중 매칭 허용 — 한 종목이 여러 카테고리에 동시 속할 수 있음
 * (예: volume_spike=true 면 모멘텀 + 이슈 둘 다).
 */
@Component
public class RecommendationFilterMatcher {

    private static final String MOMENTUM = "모멘텀";
    private static final String VALUE    = "가치";
    private static final String ISSUE    = "이슈";

    private static final double VALUE_PER_THRESHOLD = 10.0;

    public List<String> matchFilters(DiscoveryRow row) {
        List<String> filters = new ArrayList<>(3);
        if (isMomentum(row)) filters.add(MOMENTUM);
        if (isValue(row))    filters.add(VALUE);
        if (isIssue(row))    filters.add(ISSUE);
        return filters;
    }

    private boolean isMomentum(DiscoveryRow row) {
        return "high_growth".equals(row.growthStatus())
                || Boolean.TRUE.equals(row.volumeSpike());
    }

    private boolean isValue(DiscoveryRow row) {
        if ("undervalued".equals(row.valuationStatus())) return true;
        Double per = row.per();
        return per != null && per > 0 && per < VALUE_PER_THRESHOLD;
    }

    private boolean isIssue(DiscoveryRow row) {
        return Boolean.TRUE.equals(row.volumeSpike());
    }
}
