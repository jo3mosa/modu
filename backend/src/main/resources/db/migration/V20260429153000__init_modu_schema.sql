-- =====================================================================
-- MODU 주식 트레이딩 플랫폼 초기 스키마
-- =====================================================================

-- =====================================================================
-- 1. 사용자 및 공통 도메인
-- =====================================================================

CREATE TABLE users (
    id                    BIGSERIAL     NOT NULL,
    provider_id           VARCHAR       NOT NULL,
    provider              VARCHAR(20)   NOT NULL,
    phone_number          VARCHAR       NOT NULL,
    nickname              VARCHAR       NOT NULL,
    is_news_notify_enabled BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ   NOT NULL,
    updated_at            TIMESTAMPTZ   NOT NULL,
    deleted_at            TIMESTAMPTZ
);

CREATE TABLE kis_credentials (
    user_id          BIGINT       NOT NULL,
    app_key_enc      VARCHAR      NOT NULL,
    app_secret_enc   VARCHAR      NOT NULL,
    account_no       VARCHAR      NOT NULL,
    account_prdt_cd  VARCHAR(2)   NOT NULL,
    is_real_account  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL
);

CREATE TABLE refresh_tokens (
    id          BIGSERIAL    NOT NULL,
    user_id     BIGINT       NOT NULL,
    token_hash  VARCHAR      NOT NULL,
    issued_at   TIMESTAMPTZ  NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    revoked_at  TIMESTAMPTZ
);

CREATE TABLE kis_tokens_log (
    id            BIGSERIAL    NOT NULL,
    user_id       BIGINT       NOT NULL,
    token_type    VARCHAR(20)  NOT NULL,
    access_token  VARCHAR      NOT NULL,
    issued_at     TIMESTAMPTZ  NOT NULL,
    expires_at    TIMESTAMPTZ  NOT NULL,
    is_revoked    BOOLEAN      NOT NULL DEFAULT FALSE
);


-- =====================================================================
-- 2. 주식 마스터 도메인
-- =====================================================================

CREATE TABLE stock_master (
    id           BIGSERIAL    NOT NULL,
    stock_code   VARCHAR      NOT NULL,
    stock_name   VARCHAR      NOT NULL,
    market_type  VARCHAR(20)  NOT NULL,
    sector       VARCHAR,
    is_active    BOOLEAN      NOT NULL DEFAULT TRUE,
    listed_at    DATE,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL
);


-- =====================================================================
-- 3. 주문 및 체결 도메인
-- =====================================================================

CREATE TABLE orders (
    id               BIGSERIAL    NOT NULL,
    user_id          BIGINT       NOT NULL,
    stock_code       VARCHAR      NOT NULL,
    parent_order_id  BIGINT,
    side             VARCHAR(20)  NOT NULL,
    order_type       VARCHAR(20)  NOT NULL,
    quantity         BIGINT       NOT NULL,
    limit_price      BIGINT,
    filled_quantity  BIGINT       NOT NULL,
    filled_avg_price BIGINT,
    status           VARCHAR(20)  NOT NULL,
    source           VARCHAR(20)  NOT NULL,
    kis_order_no     VARCHAR,
    kis_rsvn_seq     VARCHAR,
    idempotency_key  VARCHAR      NOT NULL,
    rule_history_id  BIGINT,
    reject_reason    VARCHAR,
    cancelled_at     TIMESTAMPTZ,
    submitted_at     TIMESTAMPTZ,
    filled_at        TIMESTAMPTZ,
    commission       BIGINT       NOT NULL,
    tax              BIGINT       NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL
);

CREATE TABLE order_executions (
    id                 BIGSERIAL    NOT NULL,
    user_id            BIGINT       NOT NULL,
    order_id           BIGINT       NOT NULL,
    kis_execution_no   VARCHAR      NOT NULL,
    executed_quantity  BIGINT       NOT NULL,
    executed_price     BIGINT       NOT NULL,
    executed_amount    BIGINT       NOT NULL,
    executed_at        TIMESTAMPTZ  NOT NULL,
    received_at        TIMESTAMPTZ  NOT NULL
);


-- =====================================================================
-- 4. 포트폴리오 및 관심종목 도메인
-- =====================================================================

CREATE TABLE trade_pnl_records (
    id            BIGSERIAL    NOT NULL,
    stock_code    VARCHAR      NOT NULL,
    user_id       BIGINT       NOT NULL,
    buy_order_id  BIGINT       NOT NULL,
    sell_order_id BIGINT       NOT NULL,
    quantity      BIGINT       NOT NULL,
    avg_buy_price BIGINT       NOT NULL,
    sell_price    BIGINT       NOT NULL,
    gross_pnl     BIGINT       NOT NULL,
    commission    BIGINT       NOT NULL,
    tax           BIGINT       NOT NULL,
    net_pnl       BIGINT       NOT NULL,
    holding_days  BIGINT       NOT NULL,
    closed_at     TIMESTAMPTZ  NOT NULL
);

CREATE TABLE watchlist_groups (
    id          BIGSERIAL    NOT NULL,
    user_id     BIGINT       NOT NULL,
    group_name  VARCHAR      NOT NULL,
    is_default  BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order  BIGINT       NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL
);

CREATE TABLE watchlists (
    id                  BIGSERIAL    NOT NULL,
    stock_code          VARCHAR      NOT NULL,
    user_id             BIGINT       NOT NULL,
    watchlist_group_id  BIGINT       NOT NULL,
    added_at            TIMESTAMPTZ  NOT NULL
);

CREATE TABLE daily_portfolio_snapshots (
    id                 BIGSERIAL  NOT NULL,
    user_id            BIGINT     NOT NULL,
    snapshot_date      DATE       NOT NULL,
    total_asset        BIGINT     NOT NULL,
    available_cash     BIGINT     NOT NULL,
    total_eval_amount  BIGINT     NOT NULL,
    total_buy_amount   BIGINT     NOT NULL,
    total_pnl          BIGINT     NOT NULL,
    total_pnl_pct      BIGINT     NOT NULL,
    holdings_detail    JSONB      NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL
);


-- =====================================================================
-- 5. AI 및 자동매매 도메인
-- =====================================================================

CREATE TABLE auto_trade_settings (
    user_id                   BIGINT       NOT NULL,
    auto_trade_status         VARCHAR(20)  NOT NULL,
    kill_switch_reason        VARCHAR,
    kill_switch_triggered_at  TIMESTAMPTZ,
    created_at                TIMESTAMPTZ  NOT NULL,
    updated_at                TIMESTAMPTZ  NOT NULL
);

CREATE TABLE trading_targets (
    id          BIGSERIAL    NOT NULL,
    user_id     BIGINT       NOT NULL,
    stock_code  VARCHAR      NOT NULL,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    added_at    TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL
);

CREATE TABLE trading_rules (
    user_id                  BIGINT   NOT NULL,
    stop_loss_pct            BIGINT   NOT NULL,
    take_profit_pct          BIGINT   NOT NULL,
    daily_loss_limit_pct     BIGINT   NOT NULL,
    max_order_amount         BIGINT   NOT NULL,
    max_single_stock_pct     BIGINT   NOT NULL,
    max_holding_stocks       BIGINT   NOT NULL,
    natural_language_rule    VARCHAR,
    parsed_rule_json         JSONB,
    version                  BIGINT   NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL,
    updated_at               TIMESTAMPTZ NOT NULL
);

CREATE TABLE trading_rule_histories (
    id                    BIGSERIAL    NOT NULL,
    user_id               BIGINT       NOT NULL,
    stop_loss_pct         BIGINT       NOT NULL,
    take_profit_pct       BIGINT       NOT NULL,
    daily_loss_limit_pct  BIGINT       NOT NULL,
    max_order_amount      BIGINT       NOT NULL,
    max_single_stock_pct  BIGINT       NOT NULL,
    max_holding_stocks    BIGINT       NOT NULL,
    natural_language_rule VARCHAR,
    parsed_rule_json      JSONB,
    version_no            BIGINT       NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL
);

CREATE TABLE ai_judgments (
    id                   BIGSERIAL    NOT NULL,
    user_id              BIGINT       NOT NULL,
    stock_code           VARCHAR      NOT NULL,
    order_id             BIGINT,
    rule_history_id      BIGINT,
    decision             VARCHAR(20)  NOT NULL,
    confidence_score     BIGINT       NOT NULL,
    indicators_snapshot  JSONB        NOT NULL,
    judgment_reason      VARCHAR      NOT NULL,
    judged_at            TIMESTAMPTZ  NOT NULL
);

CREATE TABLE position_locks (
    id          BIGSERIAL    NOT NULL,
    user_id     BIGINT       NOT NULL,
    stock_code  VARCHAR      NOT NULL,
    lock_token  VARCHAR      NOT NULL,
    locked_at   TIMESTAMPTZ  NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL
);


-- =====================================================================
-- 6. 투자 성향 및 채팅 도메인
-- =====================================================================

CREATE TABLE investment_profiles (
    user_id          BIGINT       NOT NULL,
    risk_score       BIGINT       NOT NULL,
    risk_grade       VARCHAR(20)  NOT NULL,
    profile_summary  VARCHAR,
    investment_goal  VARCHAR,
    answers_snapshot JSONB        NOT NULL,
    version          BIGINT       NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL
);

CREATE TABLE profile_histories (
    id               BIGSERIAL    NOT NULL,
    user_id          BIGINT       NOT NULL,
    risk_score       BIGINT       NOT NULL,
    risk_grade       VARCHAR(20)  NOT NULL,
    investment_goal  VARCHAR,
    answers_snapshot JSONB        NOT NULL,
    version_no       BIGINT       NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL
);

CREATE TABLE chat_sessions (
    id               BIGSERIAL    NOT NULL,
    user_id          BIGINT       NOT NULL,
    title            VARCHAR,
    context_summary  VARCHAR,
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL,
    last_message_at  TIMESTAMPTZ  NOT NULL
);

CREATE TABLE chat_messages (
    id                   BIGSERIAL    NOT NULL,
    session_id           BIGINT       NOT NULL,
    user_id              BIGINT       NOT NULL,
    role                 VARCHAR(20)  NOT NULL,
    content              VARCHAR      NOT NULL,
    related_order_id     BIGINT,
    related_judgment_id  BIGINT,
    token_count          BIGINT,
    created_at           TIMESTAMPTZ  NOT NULL
);

CREATE TABLE dead_letter_queue (
    id              BIGSERIAL    NOT NULL,
    topic           VARCHAR      NOT NULL,
    partition_no    BIGINT       NOT NULL,
    offset_value    BIGINT       NOT NULL,
    payload         JSONB        NOT NULL,
    error_message   VARCHAR,
    retry_count     BIGINT       NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    resolved_at     TIMESTAMPTZ
);


-- =====================================================================
-- PRIMARY KEYS
-- =====================================================================
ALTER TABLE users                      ADD CONSTRAINT PK_USERS                      PRIMARY KEY (id);
ALTER TABLE kis_credentials            ADD CONSTRAINT PK_KIS_CREDENTIALS            PRIMARY KEY (user_id);
ALTER TABLE refresh_tokens             ADD CONSTRAINT PK_REFRESH_TOKENS             PRIMARY KEY (id);
ALTER TABLE kis_tokens_log             ADD CONSTRAINT PK_KIS_TOKENS_LOG             PRIMARY KEY (id);

ALTER TABLE stock_master               ADD CONSTRAINT PK_STOCK_MASTER               PRIMARY KEY (id);
ALTER TABLE stock_master               ADD CONSTRAINT UQ_STOCK_MASTER_CODE          UNIQUE (stock_code);

ALTER TABLE orders                     ADD CONSTRAINT PK_ORDERS                     PRIMARY KEY (id);
ALTER TABLE order_executions           ADD CONSTRAINT PK_ORDER_EXECUTIONS           PRIMARY KEY (id);

ALTER TABLE trade_pnl_records          ADD CONSTRAINT PK_TRADE_PNL_RECORDS          PRIMARY KEY (id);
ALTER TABLE watchlist_groups           ADD CONSTRAINT PK_WATCHLIST_GROUPS           PRIMARY KEY (id);
ALTER TABLE watchlists                 ADD CONSTRAINT PK_WATCHLISTS                 PRIMARY KEY (id);
ALTER TABLE daily_portfolio_snapshots  ADD CONSTRAINT PK_DAILY_PORTFOLIO_SNAPSHOTS  PRIMARY KEY (id);

ALTER TABLE auto_trade_settings        ADD CONSTRAINT PK_AUTO_TRADE_SETTINGS        PRIMARY KEY (user_id);
ALTER TABLE trading_targets            ADD CONSTRAINT PK_TRADING_TARGETS            PRIMARY KEY (id);
ALTER TABLE trading_rules              ADD CONSTRAINT PK_TRADING_RULES              PRIMARY KEY (user_id);
ALTER TABLE trading_rule_histories     ADD CONSTRAINT PK_TRADING_RULE_HISTORIES     PRIMARY KEY (id);
ALTER TABLE ai_judgments               ADD CONSTRAINT PK_AI_JUDGMENTS               PRIMARY KEY (id);
ALTER TABLE position_locks             ADD CONSTRAINT PK_POSITION_LOCKS             PRIMARY KEY (id);

ALTER TABLE investment_profiles        ADD CONSTRAINT PK_INVESTMENT_PROFILES        PRIMARY KEY (user_id);
ALTER TABLE profile_histories          ADD CONSTRAINT PK_PROFILE_HISTORIES          PRIMARY KEY (id);
ALTER TABLE chat_sessions              ADD CONSTRAINT PK_CHAT_SESSIONS              PRIMARY KEY (id);
ALTER TABLE chat_messages              ADD CONSTRAINT PK_CHAT_MESSAGES              PRIMARY KEY (id);
ALTER TABLE dead_letter_queue          ADD CONSTRAINT PK_DEAD_LETTER_QUEUE          PRIMARY KEY (id);


-- =====================================================================
-- FOREIGN KEYS
-- =====================================================================

-- users 참조
ALTER TABLE kis_credentials           ADD CONSTRAINT FK_USERS_TO_KIS_CREDS         FOREIGN KEY (user_id)            REFERENCES users (id);
ALTER TABLE refresh_tokens            ADD CONSTRAINT FK_USERS_TO_TOKENS             FOREIGN KEY (user_id)            REFERENCES users (id);
ALTER TABLE kis_tokens_log            ADD CONSTRAINT FK_USERS_TO_KIS_LOG            FOREIGN KEY (user_id)            REFERENCES users (id);
ALTER TABLE orders                    ADD CONSTRAINT FK_USERS_TO_ORDERS             FOREIGN KEY (user_id)            REFERENCES users (id);
ALTER TABLE order_executions          ADD CONSTRAINT FK_USERS_TO_EXECUTIONS         FOREIGN KEY (user_id)            REFERENCES users (id);
ALTER TABLE trade_pnl_records         ADD CONSTRAINT FK_USERS_TO_PNL               FOREIGN KEY (user_id)            REFERENCES users (id);
ALTER TABLE watchlist_groups          ADD CONSTRAINT FK_USERS_TO_WL_GROUPS          FOREIGN KEY (user_id)            REFERENCES users (id);
ALTER TABLE watchlists                ADD CONSTRAINT FK_USERS_TO_WL                 FOREIGN KEY (user_id)            REFERENCES users (id);
ALTER TABLE daily_portfolio_snapshots ADD CONSTRAINT FK_USERS_TO_SNAPSHOTS          FOREIGN KEY (user_id)            REFERENCES users (id);
ALTER TABLE auto_trade_settings       ADD CONSTRAINT FK_USERS_TO_AUTO_TRADE         FOREIGN KEY (user_id)            REFERENCES users (id);
ALTER TABLE trading_targets           ADD CONSTRAINT FK_USERS_TO_TARGETS            FOREIGN KEY (user_id)            REFERENCES users (id);
ALTER TABLE trading_rules             ADD CONSTRAINT FK_USERS_TO_RULES              FOREIGN KEY (user_id)            REFERENCES users (id);
ALTER TABLE trading_rule_histories    ADD CONSTRAINT FK_USERS_TO_RULE_HISTORIES     FOREIGN KEY (user_id)            REFERENCES users (id);
ALTER TABLE ai_judgments              ADD CONSTRAINT FK_USERS_TO_JUDGMENTS          FOREIGN KEY (user_id)            REFERENCES users (id);
ALTER TABLE position_locks            ADD CONSTRAINT FK_USERS_TO_LOCKS              FOREIGN KEY (user_id)            REFERENCES users (id);
ALTER TABLE investment_profiles       ADD CONSTRAINT FK_USERS_TO_PROFILES           FOREIGN KEY (user_id)            REFERENCES users (id);
ALTER TABLE profile_histories         ADD CONSTRAINT FK_USERS_TO_PROF_HISTORIES     FOREIGN KEY (user_id)            REFERENCES users (id);
ALTER TABLE chat_sessions             ADD CONSTRAINT FK_USERS_TO_CHAT_SESS          FOREIGN KEY (user_id)            REFERENCES users (id);
ALTER TABLE chat_messages             ADD CONSTRAINT FK_USERS_TO_CHAT_MSG           FOREIGN KEY (user_id)            REFERENCES users (id);

-- stock_master 참조 (stock_code 기준)
ALTER TABLE orders                    ADD CONSTRAINT FK_STOCK_TO_ORDERS             FOREIGN KEY (stock_code)         REFERENCES stock_master (stock_code);
ALTER TABLE trade_pnl_records         ADD CONSTRAINT FK_STOCK_TO_PNL               FOREIGN KEY (stock_code)         REFERENCES stock_master (stock_code);
ALTER TABLE watchlists                ADD CONSTRAINT FK_STOCK_TO_WL                 FOREIGN KEY (stock_code)         REFERENCES stock_master (stock_code);
ALTER TABLE trading_targets           ADD CONSTRAINT FK_STOCK_TO_TARGETS            FOREIGN KEY (stock_code)         REFERENCES stock_master (stock_code);
ALTER TABLE ai_judgments              ADD CONSTRAINT FK_STOCK_TO_JUDGMENTS          FOREIGN KEY (stock_code)         REFERENCES stock_master (stock_code);
ALTER TABLE position_locks            ADD CONSTRAINT FK_STOCK_TO_LOCKS              FOREIGN KEY (stock_code)         REFERENCES stock_master (stock_code);

-- 도메인 내부 참조
ALTER TABLE orders                    ADD CONSTRAINT FK_ORDERS_SELF_PARENT          FOREIGN KEY (parent_order_id)    REFERENCES orders (id);
ALTER TABLE watchlists                ADD CONSTRAINT FK_WL_GROUP_TO_WL              FOREIGN KEY (watchlist_group_id) REFERENCES watchlist_groups (id);
ALTER TABLE chat_messages             ADD CONSTRAINT FK_CHAT_SESS_TO_MSG            FOREIGN KEY (session_id)         REFERENCES chat_sessions (id);
