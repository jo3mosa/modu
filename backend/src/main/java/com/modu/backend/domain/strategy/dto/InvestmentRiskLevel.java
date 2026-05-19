package com.modu.backend.domain.strategy.dto;

public enum InvestmentRiskLevel {
    STABLE("안정형", "초저위험", 1),
    STABLE_SEEKING("안정추구형", "저위험", 2),
    RISK_NEUTRAL("위험중립형", "중위험", 3),
    ACTIVE("적극투자형", "고위험", 4),
    AGGRESSIVE("공격투자형", "초고위험", 5);

    private final String label;
    private final String maxProductRisk;
    private final int grade;

    InvestmentRiskLevel(String label, String maxProductRisk, int grade) {
        this.label = label;
        this.maxProductRisk = maxProductRisk;
        this.grade = grade;
    }

    public String getLabel() {
        return label;
    }

    public String getMaxProductRisk() {
        return maxProductRisk;
    }

    /**
     * 1~5 정수 매핑 (Redis users:by_grade:{1~5} 키 산출용).
     * 외부 계약 안정성을 위해 ordinal() 이 아닌 명시 field 사용 — enum 선언 순서 변경에 무영향.
     */
    public int toGradeInt() {
        return grade;
    }

    public static InvestmentRiskLevel fromScore(long score) {
        if (score <= 20) {
            return STABLE;
        }
        if (score <= 40) {
            return STABLE_SEEKING;
        }
        if (score <= 60) {
            return RISK_NEUTRAL;
        }
        if (score <= 80) {
            return ACTIVE;
        }
        return AGGRESSIVE;
    }
}
