-- =====================================================================
-- Discovery API (S14P31B106-362) 가 의존하는 컬럼 추가
--
-- 원인:
--   DA 측 compute_fundamental_ranks.py / compute_volume_spike.py 가
--   런타임에 ALTER TABLE ... IF NOT EXISTS 로 추가하지만, DA 적재 스크립트
--   미실행 환경에서 BE 가 SELECT 시도 시 "column does not exist" 에러.
--
-- 354 의 risk_tier 추가 패턴과 일관:
--   BE 가 컬럼 자체는 Flyway 로 보장, DA 가 값 채우기는 그대로 자기 영역.
--   DA 의 ADD COLUMN IF NOT EXISTS 와 충돌 없음 (idempotent).
--
-- 컬럼:
--   daily_fundamentals.roe_rank_pct  : 일별 cross-sectional ROE 백분위 순위 (0~1).
--                                       0=최고, 1=최저. NULL=ROE NULL 또는 미산출.
--                                       Discovery 응답 정렬 기준.
--   daily_indicators.volume_spike    : 직전 20일 평균 대비 2배 이상 거래량 boolean.
--                                       Discovery 의 "모멘텀" / "이슈" filter 매핑.
-- =====================================================================

ALTER TABLE daily_fundamentals
    ADD COLUMN IF NOT EXISTS roe_rank_pct DOUBLE PRECISION;

ALTER TABLE daily_indicators
    ADD COLUMN IF NOT EXISTS volume_spike BOOLEAN;

COMMENT ON COLUMN daily_fundamentals.roe_rank_pct IS
    'Cross-sectional ROE 백분위 순위 (0=최고 ~ 1=최저). NULL=ROE NULL 또는 미산출. DA compute_fundamental_ranks.py 적재.';
COMMENT ON COLUMN daily_indicators.volume_spike IS
    '직전 20일 평균 대비 2배 이상 거래량 boolean. NULL=미산출. DA compute_volume_spike.py 적재.';
