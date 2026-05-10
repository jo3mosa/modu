ALTER TABLE trading_rules
    ADD COLUMN max_daily_order_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN daily_loss_limit_amount BIGINT NOT NULL DEFAULT 0;

ALTER TABLE trading_rule_histories
    ADD COLUMN max_daily_order_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN daily_loss_limit_amount BIGINT NOT NULL DEFAULT 0;

ALTER TABLE trading_rules
    DROP COLUMN daily_loss_limit_pct,
    DROP COLUMN max_order_amount,
    DROP COLUMN max_single_stock_pct,
    DROP COLUMN max_holding_stocks;

ALTER TABLE trading_rule_histories
    DROP COLUMN daily_loss_limit_pct,
    DROP COLUMN max_order_amount,
    DROP COLUMN max_single_stock_pct,
    DROP COLUMN max_holding_stocks;

ALTER TABLE trading_rules
    ALTER COLUMN max_daily_order_count DROP DEFAULT,
    ALTER COLUMN daily_loss_limit_amount DROP DEFAULT;

ALTER TABLE trading_rule_histories
    ALTER COLUMN max_daily_order_count DROP DEFAULT,
    ALTER COLUMN daily_loss_limit_amount DROP DEFAULT;
