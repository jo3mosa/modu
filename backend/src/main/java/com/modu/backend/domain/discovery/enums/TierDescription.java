package com.modu.backend.domain.discovery.enums;

/**
 * Risk tier 별 라벨 / 설명 — S14P31B106-362
 *
 * DA 의 compute_risk_tier.py 분류 정책에 대응되는 사용자 표시용 메타데이터.
 * BE 가 응답에 항상 포함 (T1~T5 모두). FE 가 사용자 등급 이하 tier 만 노출.
 */
public enum TierDescription {
    T1(1, "안정형", "저변동성 KOSPI 대형주. 안정성·수익성 중심."),
    T2(2, "안정추구형", "안정형 + 완만한 성장. 적자 종목 제외."),
    T3(3, "위험중립형", "성장과 안정 균형. 변동성 중간 수준."),
    T4(4, "적극투자형", "고성장 모멘텀. 변동성 감내 가능 시."),
    T5(5, "공격투자형", "고변동성 종목. 본인 등급 도달 시 해금.");

    private final int tierNumber;
    private final String label;
    private final String description;

    TierDescription(int tierNumber, String label, String description) {
        this.tierNumber = tierNumber;
        this.label = label;
        this.description = description;
    }

    public int getTierNumber() { return tierNumber; }
    public String getLabel() { return label; }
    public String getDescription() { return description; }

    public String getTierKey() { return "T" + tierNumber; }

    public static TierDescription fromNumber(int n) {
        for (TierDescription t : values()) {
            if (t.tierNumber == n) return t;
        }
        throw new IllegalArgumentException("Unknown tier number: " + n);
    }
}
