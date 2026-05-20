package com.modu.backend.domain.discovery.service;

import com.modu.backend.domain.discovery.repository.DiscoveryRow;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 종목별 추천 사유 (reason) 정적 템플릿 생성 — S14P31B106-362
 *
 * 매핑된 tags 가 있으면 tags 를 연결한 짧은 문장을 만들고,
 * tags 가 비어있으면 tier 기준 일반 문구로 fallback.
 *
 * 예:
 *   tags=["저평가", "고수익"]        → "저평가 + 고수익 종목입니다."
 *   tags=["안정성 우수"]             → "안정성 우수 종목입니다."
 *   tags=[] + tier=1                → "안정형 등급에 부합하는 종목입니다."
 *   tags=[] + tier=5                → "공격투자형 등급에 부합하는 종목입니다."
 *
 * AI / DA 의 자유 큐레이션 텍스트 (예: "HBM3E 양산 본격화") 는 후속 task 영역.
 */
@Component
public class RecommendationReasonBuilder {

    private static final String[] TIER_FALLBACK_LABELS = {
            "안정형", "안정추구형", "위험중립형", "적극투자형", "공격투자형"
    };

    public String build(DiscoveryRow row, List<String> tags) {
        if (tags != null && !tags.isEmpty()) {
            return String.join(" + ", tags) + " 종목입니다.";
        }
        Integer tier = row.riskTier();
        if (tier != null && tier >= 1 && tier <= 5) {
            return TIER_FALLBACK_LABELS[tier - 1] + " 등급에 부합하는 종목입니다.";
        }
        return "추천 종목입니다.";
    }
}
