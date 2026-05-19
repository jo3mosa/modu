-- =====================================================================
-- ai_judgments.stock_tier / matched_risk_grade 컬럼 타입을 INTEGER 로 변경
-- 관련 이슈: S14P31B106-354 (Risk Tier 비보유자 매수 추천)
--
-- 원인:
--   V20260519220100 에서 SMALLINT 로 생성했으나, AiJudgment 엔티티가 Integer
--   (JDBC INTEGER) 로 매핑되어 Hibernate schema-validation 실패.
--   ("found [int2 (Types#SMALLINT)], but expecting [integer (Types#INTEGER)]")
--
-- 변경:
--   SMALLINT → INTEGER. CHECK (1~5) 제약은 동일하게 유지.
--   PostgreSQL 의 SMALLINT → INTEGER 변환은 implicit cast 라 USING 절 불필요.
-- =====================================================================

ALTER TABLE ai_judgments
    ALTER COLUMN stock_tier         TYPE INTEGER,
    ALTER COLUMN matched_risk_grade TYPE INTEGER;
