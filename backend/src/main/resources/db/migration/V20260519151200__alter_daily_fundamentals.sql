-- ============================================================
-- daily_fundamentals.risk_tier 컬럼 추가
--
-- 목적:
--   종목을 5단계 위험도(1=안정형 ~ 5=공격투자형)로 분류해두고,
--   트리거 라우팅 시 `user.risk_grade >= stock.risk_tier` 매칭에 사용.
--   (기존 보유자 기반 라우팅 ∪ tier 매칭 유저로 확장)
--
-- 산출 책임:
--   analysis_server/scripts/backfill/compute_risk_tier.py 가
--   stability_status / atr_ratio / market_type 등 기존 컬럼으로 분류하여 UPDATE.
--   backend 는 SELECT 만 (analysis_server 가 single writer).
--
-- 값 도메인 (InvestmentRiskLevel enum 과 1:1 매핑):
--   1 = STABLE          (안정형)
--   2 = STABLE_SEEKING  (안정추구형)
--   3 = RISK_NEUTRAL    (위험중립형)
--   4 = ACTIVE          (적극투자형)
--   5 = AGGRESSIVE      (공격투자형)
--   NULL = 미분류 (신규 상장 / 데이터 부족 / 분류 스크립트 미실행 시)
--
-- 조회 패턴:
--   매 트리거당 1회: stock_code 의 최신일 risk_tier SELECT.
--   기존 PK (stock_code, date) 인덱스로 커버 → 별도 인덱스 추가 없음.
-- ============================================================

ALTER TABLE daily_fundamentals
    ADD COLUMN IF NOT EXISTS risk_tier SMALLINT;

-- CHECK 제약 — PostgreSQL 은 ADD CONSTRAINT IF NOT EXISTS 미지원이라
-- pg_constraint 사전 조회 후 분기 (dev 재실행 대비 idempotent).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_daily_fundamentals_risk_tier'
    ) THEN
        ALTER TABLE daily_fundamentals
            ADD CONSTRAINT chk_daily_fundamentals_risk_tier
            CHECK (risk_tier IS NULL OR risk_tier BETWEEN 1 AND 5);
    END IF;
END $$;

COMMENT ON COLUMN daily_fundamentals.risk_tier IS
    '5단계 위험도 (1=STABLE ~ 5=AGGRESSIVE). NULL=미분류. analysis_server compute_risk_tier.py 가 산출.';