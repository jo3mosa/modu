-- ============================================================
-- analysis_server SQLite → Postgres 마이그레이션 DDL
--
-- 백엔드에 전달:
--   아래 4 테이블을 modu_db (또는 분석 전용 schema) 에 생성해주세요.
--   현재 분석 서버는 SQLite `data/stock_master.db` 에 보관 중이고,
--   stock_master 는 backend 가 이미 Postgres 에 적재 완료 (재사용).
--
-- 데이터 규모 (현재 SQLite 기준):
--   daily_ohlcv          ~3.18M rows  (pykrx 일봉 5년치)
--   daily_fundamentals   ~1.73M rows  (일별 재무 비율 panel)
--   daily_indicators     ~1.27M rows  (일별 기술 지표 panel — AI 학습용)
--   financial_statements ~11K rows    (DART 사업보고서 원본)
--
-- schema 선택:
--   기본 = public. analysis schema 분리 원하면 맨 위 두 줄 활성화.
-- ============================================================

-- CREATE SCHEMA IF NOT EXISTS analysis;
-- SET search_path TO analysis, public;


-- ============================================================
-- 1. daily_ohlcv — pykrx 일봉
--    PK (stock_code, date), 날짜는 DATE 타입 (YYYY-MM-DD).
--    volume 은 BIGINT (코스닥 일부 종목 단일일 거래량 INT MAX 초과 가능).
-- ============================================================
CREATE TABLE IF NOT EXISTS daily_ohlcv (
    stock_code  VARCHAR(10) NOT NULL,
    date        DATE        NOT NULL,
    open        INTEGER     NOT NULL,
    high        INTEGER     NOT NULL,
    low         INTEGER     NOT NULL,
    close       INTEGER     NOT NULL,
    volume      BIGINT      NOT NULL,
    PRIMARY KEY (stock_code, date)
);

-- 날짜 단독 조회용 (전체 시장 특정일 스냅샷 등)
CREATE INDEX IF NOT EXISTS idx_daily_ohlcv_date
    ON daily_ohlcv(date);


-- ============================================================
-- 2. daily_fundamentals — 일별 재무 비율 panel
--    PK (stock_code, date). 비율은 DOUBLE PRECISION.
--    status 컬럼은 enum-like 짧은 문자열 (VARCHAR(20)).
-- ============================================================
CREATE TABLE IF NOT EXISTS daily_fundamentals (
    stock_code            VARCHAR(10) NOT NULL,
    date                  DATE        NOT NULL,
    fiscal_year           INTEGER,                 -- 해당 시점 사용된 사업연도 (point-in-time)
    -- per-share
    eps                   DOUBLE PRECISION,
    bps                   DOUBLE PRECISION,
    -- valuation (가격 의존, 매일 변동)
    per                   DOUBLE PRECISION,
    pbr                   DOUBLE PRECISION,
    -- profitability
    roe                   DOUBLE PRECISION,        -- %
    -- stability ratios
    debt_ratio            DOUBLE PRECISION,        -- 부채총계 / 자본총계 * 100
    current_ratio         DOUBLE PRECISION,        -- 유동자산 / 유동부채 * 100
    -- growth (YoY)
    revenue_growth        DOUBLE PRECISION,        -- (curr - prev) / |prev| * 100
    operating_growth      DOUBLE PRECISION,
    -- 분류 (engine.signal_builder classify_* 함수 결과)
    valuation_status      VARCHAR(20),             -- undervalued / fair / overvalued / unknown
    profitability_status  VARCHAR(20),             -- high_margin / normal / low_margin / unknown
    growth_status         VARCHAR(20),             -- high_growth / steady_growth / stagnant / declining / unknown
    stability_status      VARCHAR(20),             -- stable / moderate / risky / unknown
    PRIMARY KEY (stock_code, date)
);

CREATE INDEX IF NOT EXISTS idx_daily_fundamentals_date
    ON daily_fundamentals(date);


-- ============================================================
-- 3. daily_indicators — 일별 기술 지표 panel (AI 학습용)
--    PK (stock_code, date). 17 개 지표 + 분류 + 캔들 패턴.
-- ============================================================
CREATE TABLE IF NOT EXISTS daily_indicators (
    stock_code            VARCHAR(10) NOT NULL,
    date                  DATE        NOT NULL,
    -- 추세
    sma_5                 DOUBLE PRECISION,
    sma_20                DOUBLE PRECISION,
    sma_60                DOUBLE PRECISION,
    macd                  DOUBLE PRECISION,
    macd_signal           DOUBLE PRECISION,
    sma_alignment         VARCHAR(20),             -- bullish_aligned / bearish_aligned / mixed
    macd_state            VARCHAR(20),             -- bullish_cross / bearish_cross / uptrend / downtrend / mixed
    -- 모멘텀
    rsi_14                DOUBLE PRECISION,
    -- 변동성
    bb_upper              DOUBLE PRECISION,
    bb_lower              DOUBLE PRECISION,
    bollinger_position    VARCHAR(20),             -- upper_breakout / lower_breakout / inside_band
    atr                   DOUBLE PRECISION,
    atr_ratio             DOUBLE PRECISION,
    -- 거래량
    mfi_14                DOUBLE PRECISION,
    -- 캔들 패턴 (단일봉 형태)
    candle_pattern        VARCHAR(30),             -- doji / hammer / shooting_star / bullish_marubozu / bearish_marubozu / long_bullish / long_bearish / normal / flat
    candle_body_ratio     DOUBLE PRECISION,        -- |close-open| / (high-low)
    upper_shadow_ratio    DOUBLE PRECISION,        -- (high - max(open,close)) / (high-low)
    lower_shadow_ratio    DOUBLE PRECISION,        -- (min(open,close) - low) / (high-low)
    gap_ratio             DOUBLE PRECISION,        -- (open[t] - close[t-1]) / close[t-1]
    -- 과거 수익률 (look-back, feature 용 — NOT forward)
    return_1d             DOUBLE PRECISION,
    return_5d             DOUBLE PRECISION,
    PRIMARY KEY (stock_code, date)
);

CREATE INDEX IF NOT EXISTS idx_daily_indicators_date
    ON daily_indicators(date);


-- ============================================================
-- 4. financial_statements — DART 사업보고서 원본
--    PK (stock_code, fiscal_year, reprt_code).
--    재무 금액은 BIGINT (원 단위, 조 원 규모 가능).
--    reprt_code: 11011=사업, 11012=반기, 11013=Q1, 11014=Q3.
-- ============================================================
CREATE TABLE IF NOT EXISTS financial_statements (
    stock_code           VARCHAR(10)  NOT NULL,
    fiscal_year          INTEGER      NOT NULL,
    reprt_code           VARCHAR(10)  NOT NULL DEFAULT '11011',
    revenue              BIGINT,                    -- 매출액 (원)
    operating_income     BIGINT,                    -- 영업이익
    net_income           BIGINT,                    -- 당기순이익
    total_assets         BIGINT,                    -- 자산총계
    total_liabilities    BIGINT,                    -- 부채총계
    total_equity         BIGINT,                    -- 자본총계
    current_assets       BIGINT,                    -- 유동자산
    current_liabilities  BIGINT,                    -- 유동부채
    shares_outstanding   BIGINT,                    -- 보통주 발행주식수
    fetched_at           TIMESTAMPTZ  DEFAULT NOW(),
    PRIMARY KEY (stock_code, fiscal_year, reprt_code)
);


-- ============================================================
-- 권한 (운영 환경에서 분석 서버 전용 user 분리 시):
--
--   GRANT SELECT, INSERT, UPDATE, DELETE
--     ON daily_ohlcv, daily_fundamentals, daily_indicators, financial_statements
--     TO analysis_user;
--
-- 분석 서버는 backfill 시 INSERT/UPSERT, live 시 SELECT 위주.
-- 같은 DB 의 stock_master 는 SELECT 만 (backend 가 적재 + 갱신 담당).
-- ============================================================