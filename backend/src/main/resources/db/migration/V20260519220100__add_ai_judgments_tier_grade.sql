-- =====================================================================
-- ai_judgments 에 stock_tier + matched_risk_grade 컬럼 추가
-- 관련 이슈: S14P31B106-354 (Risk Tier 비보유자 매수 추천)
--
-- 목적:
--   RECOMMENDED 분기 시 FE 멘트 풍부화에 필요한 컨텍스트 저장
--   - stock_tier         : 추천 종목의 위험 등급 (1=STABLE ~ 5=AGGRESSIVE)
--   - matched_risk_grade : 매칭 당시 사용자의 risk_grade (1~5)
--
-- 조달 방식 (BE 자체):
--   stock_tier         = StockRiskTierRedisRepository.get(stockCode)
--   matched_risk_grade = InvestmentProfile.riskGrade → InvestmentRiskLevel.toGradeInt()
--   AI 측 페이로드에는 포함되지 않음 (is_holder 만 전송).
--
-- 값 도메인:
--   1 ~ 5, NULL 허용 (RECOMMENDED 아닌 경우 / Redis 미적재 종목 fallback).
-- =====================================================================

ALTER TABLE ai_judgments
    ADD COLUMN IF NOT EXISTS stock_tier         SMALLINT,
    ADD COLUMN IF NOT EXISTS matched_risk_grade SMALLINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_ai_judgments_stock_tier'
    ) THEN
        ALTER TABLE ai_judgments
            ADD CONSTRAINT chk_ai_judgments_stock_tier
            CHECK (stock_tier IS NULL OR stock_tier BETWEEN 1 AND 5);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_ai_judgments_matched_risk_grade'
    ) THEN
        ALTER TABLE ai_judgments
            ADD CONSTRAINT chk_ai_judgments_matched_risk_grade
            CHECK (matched_risk_grade IS NULL OR matched_risk_grade BETWEEN 1 AND 5);
    END IF;
END $$;

COMMENT ON COLUMN ai_judgments.stock_tier IS
    '비보유자 BUY 추천 시 종목의 risk_tier (1~5). 그 외 NULL.';
COMMENT ON COLUMN ai_judgments.matched_risk_grade IS
    '비보유자 BUY 추천 시 매칭된 사용자 risk_grade (1~5). 그 외 NULL.';
