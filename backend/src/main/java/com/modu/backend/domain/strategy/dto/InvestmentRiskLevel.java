package com.modu.backend.domain.strategy.dto;

public enum InvestmentRiskLevel {
    STABLE("안정형", "초저위험"),
    STABLE_SEEKING("안정추구형", "저위험"),
    RISK_NEUTRAL("위험중립형", "중위험"),
    ACTIVE("적극투자형", "고위험"),
    AGGRESSIVE("공격투자형", "초고위험");

    private final String label;
    private final String maxProductRisk;

    InvestmentRiskLevel(String label, String maxProductRisk) {
        this.label = label;
        this.maxProductRisk = maxProductRisk;
    }

    public String getLabel() {
        return label;
    }

    public String getMaxProductRisk() {
        return maxProductRisk;
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
