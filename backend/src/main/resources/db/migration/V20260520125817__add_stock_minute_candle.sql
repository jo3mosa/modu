-- 분봉 캔들 영구 캐시 — KIS 213 호출 결과 적재
-- PK: (stock_code, ts) — 멀티 pod 환경 ON CONFLICT DO NOTHING 으로 중복 INSERT 방어
CREATE TABLE IF NOT EXISTS stock_minute_candle (
    stock_code  VARCHAR(12) NOT NULL,
    ts          TIMESTAMP   NOT NULL,
    open_price  BIGINT      NOT NULL,
    high_price  BIGINT      NOT NULL,
    low_price   BIGINT      NOT NULL,
    close_price BIGINT      NOT NULL,
    volume      BIGINT      NOT NULL DEFAULT 0,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_stock_minute_candle PRIMARY KEY (stock_code, ts)
);

CREATE INDEX IF NOT EXISTS idx_stock_minute_candle_ts
    ON stock_minute_candle (stock_code, ts DESC);
