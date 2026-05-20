-- 분봉 일자 단위 적재 완료 마커 — KIS 호출 missing 일자 판단
-- 휴장일도 candle_count=0 으로 INSERT 하여 재호출 방지
CREATE TABLE IF NOT EXISTS stock_minute_candle_loaded_date (
    stock_code   VARCHAR(12) NOT NULL,
    trade_date   DATE        NOT NULL,
    candle_count INTEGER     NOT NULL DEFAULT 0,
    loaded_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_stock_minute_candle_loaded_date PRIMARY KEY (stock_code, trade_date)
);
