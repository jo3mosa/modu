-- S14P31B106-336 ReservedOrderRouting
--
-- 국내 거래일/휴장일 캐시 테이블 추가
-- KIS chk-holiday API (TR_ID=CTCA0903R) 응답을 보관하여 자동매매 흐름의
-- 정규장 / 예약주문 / reject 라우팅 판단에 사용한다.
--
-- 갱신 정책: 매일 04:00 KST 스케줄러 + 부팅 시 ApplicationRunner (캐시 결손 시)
-- 보관 범위: 갱신 시점 기준 약 90일치 (페이지네이션 4회)
-- 호출 빈도: 1일 1회 갱신 (KIS 공식 가이드 준수)

CREATE TABLE trading_calendars (
    bass_dt      DATE         NOT NULL,
    wday_dvsn_cd VARCHAR(2)   NOT NULL,
    bzdy_yn      CHAR(1)      NOT NULL,
    tr_day_yn    CHAR(1)      NOT NULL,
    opnd_yn      CHAR(1)      NOT NULL,
    sttl_day_yn  CHAR(1)      NOT NULL,
    fetched_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_trading_calendars PRIMARY KEY (bass_dt),
    CONSTRAINT chk_trading_calendars_bzdy_yn     CHECK (bzdy_yn     IN ('Y','N')),
    CONSTRAINT chk_trading_calendars_tr_day_yn   CHECK (tr_day_yn   IN ('Y','N')),
    CONSTRAINT chk_trading_calendars_opnd_yn     CHECK (opnd_yn     IN ('Y','N')),
    CONSTRAINT chk_trading_calendars_sttl_day_yn CHECK (sttl_day_yn IN ('Y','N'))
);

-- opnd_yn = 'Y' 필터링 쿼리 (다음 개장일 조회 등) 가속용 partial index
CREATE INDEX idx_trading_calendars_opnd_y
    ON trading_calendars (bass_dt)
    WHERE opnd_yn = 'Y';

COMMENT ON TABLE  trading_calendars              IS '국내 거래일/휴장일 캐시 — KIS chk-holiday 응답 (일 1회 갱신)';
COMMENT ON COLUMN trading_calendars.bass_dt      IS '기준일자';
COMMENT ON COLUMN trading_calendars.wday_dvsn_cd IS '요일구분코드 (01:일 ~ 07:토)';
COMMENT ON COLUMN trading_calendars.bzdy_yn      IS '영업일여부 (Y/N) — 은행 영업일 기준';
COMMENT ON COLUMN trading_calendars.tr_day_yn    IS '거래일여부 (Y/N) — 증권사 영업일';
COMMENT ON COLUMN trading_calendars.opnd_yn      IS '개장일여부 (Y/N) — 주식시장 개장일. 주문 가능 여부 판단 기준';
COMMENT ON COLUMN trading_calendars.sttl_day_yn  IS '결제일여부 (Y/N)';
COMMENT ON COLUMN trading_calendars.fetched_at   IS 'KIS API 로부터 데이터 수신한 시각';
