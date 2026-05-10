-- =====================================================================
-- 1. ai_judgments 컬럼 추가
-- =====================================================================
ALTER TABLE ai_judgments
    ADD COLUMN key_signals       JSONB        NOT NULL DEFAULT '[]',
    ADD COLUMN bull_claim        VARCHAR      NULL,
    ADD COLUMN bear_claim        VARCHAR      NULL,
    ADD COLUMN sector            VARCHAR      NULL,
    ADD COLUMN risk_grade        VARCHAR(20)  NULL,
    ADD COLUMN target_price      BIGINT       NULL,
    ADD COLUMN stop_loss_price   BIGINT       NULL,
    ADD COLUMN order_amount      BIGINT       NULL,
    ADD COLUMN winning_side      VARCHAR(20)  NULL,
    ADD COLUMN expected_scenario VARCHAR(20)  NULL;


-- =====================================================================
-- 2. position_thresholds 테이블 생성
-- =====================================================================
CREATE TABLE position_thresholds (
    id                     BIGSERIAL    NOT NULL,
    user_id                BIGINT       NOT NULL,
    stock_code             VARCHAR      NOT NULL,
    ai_judgment_id         BIGINT       NULL,
    source_order_id        BIGINT       NOT NULL,
    last_order_id          BIGINT       NULL,
    quantity               BIGINT       NOT NULL,
    avg_entry_price        BIGINT       NOT NULL,
    ai_target_price        BIGINT       NULL,
    ai_stop_loss_price     BIGINT       NULL,
    user_take_profit_price BIGINT       NULL,
    user_stop_loss_price   BIGINT       NULL,
    active_target_price    BIGINT       NULL,
    active_stop_loss_price BIGINT       NULL,
    triggered_reason       VARCHAR(30)  NULL,
    is_active              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMPTZ  NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL,
    closed_at              TIMESTAMPTZ  NULL
);


-- =====================================================================
-- 3. post_mortem_reports 테이블 생성
-- =====================================================================
CREATE TABLE post_mortem_reports (
    id                       BIGSERIAL    NOT NULL,
    user_id                  BIGINT       NOT NULL,
    ai_judgment_id           BIGINT       NOT NULL,
    trade_pnl_record_id      BIGINT       NULL,
    entry_timing_assessment  VARCHAR      NOT NULL,
    exit_rule_assessment     VARCHAR      NOT NULL,
    risk_prediction_accuracy VARCHAR      NOT NULL,
    missed_signals           JSONB        NOT NULL DEFAULT '[]',
    lessons                  JSONB        NOT NULL DEFAULT '[]',
    summary                  VARCHAR      NOT NULL,
    created_at               TIMESTAMPTZ  NOT NULL
);


-- =====================================================================
-- PRIMARY KEYS
-- =====================================================================
ALTER TABLE position_thresholds ADD CONSTRAINT PK_POSITION_THRESHOLDS PRIMARY KEY (id);
ALTER TABLE post_mortem_reports ADD CONSTRAINT PK_POST_MORTEM_REPORTS PRIMARY KEY (id);


-- =====================================================================
-- FOREIGN KEYS
-- =====================================================================
ALTER TABLE position_thresholds
    ADD CONSTRAINT FK_USERS_TO_POS_THRESH       FOREIGN KEY (user_id)         REFERENCES users (id),
    ADD CONSTRAINT FK_STOCK_TO_POS_THRESH       FOREIGN KEY (stock_code)      REFERENCES stock_master (stock_code),
    ADD CONSTRAINT FK_JUDG_TO_POS_THRESH        FOREIGN KEY (ai_judgment_id)  REFERENCES ai_judgments (id),
    ADD CONSTRAINT FK_SRC_ORDER_TO_POS_THRESH   FOREIGN KEY (source_order_id) REFERENCES orders (id),
    ADD CONSTRAINT FK_LAST_ORDER_TO_POS_THRESH  FOREIGN KEY (last_order_id)   REFERENCES orders (id);

ALTER TABLE post_mortem_reports
    ADD CONSTRAINT FK_USERS_TO_POST_MORTEM  FOREIGN KEY (user_id)             REFERENCES users (id),
    ADD CONSTRAINT FK_JUDG_TO_POST_MORTEM   FOREIGN KEY (ai_judgment_id)      REFERENCES ai_judgments (id),
    ADD CONSTRAINT FK_PNL_TO_POST_MORTEM    FOREIGN KEY (trade_pnl_record_id) REFERENCES trade_pnl_records (id);


-- =====================================================================
-- CHECK CONSTRAINTS
-- =====================================================================

-- 감시 중인 레코드는 반드시 실제 모니터링 기준가를 보유해야 함 (target/stop 둘 다 필수)
ALTER TABLE position_thresholds
    ADD CONSTRAINT CHK_ACTIVE_PRICES
        CHECK (is_active = FALSE OR (active_target_price IS NOT NULL OR active_stop_loss_price IS NOT NULL));

-- triggered_reason 허용 값 제한 (활성 상태일 때 NULL 허용)
ALTER TABLE position_thresholds
    ADD CONSTRAINT CHK_TRIGGERED_REASON
        CHECK (triggered_reason IS NULL OR triggered_reason IN ('USER_STOP_LOSS', 'USER_TAKE_PROFIT', 'AI_STOP_LOSS', 'AI_TAKE_PROFIT'));


-- =====================================================================
-- PARTIAL UNIQUE INDEX
-- =====================================================================

-- 동일 사용자+종목의 활성 감시 레코드는 하나만 허용 (비활성 이력은 중복 허용)
CREATE UNIQUE INDEX UQ_POSITION_THRESHOLDS_ACTIVE
    ON position_thresholds (user_id, stock_code)
    WHERE is_active = TRUE;


-- =====================================================================
-- INDEXES
-- =====================================================================

-- ai_judgments: retrieval 쿼리 기준 (user_id 필터 + judged_at 정렬)
CREATE INDEX IDX_AI_JUDGMENTS_USER_JUDGED
    ON ai_judgments (user_id, judged_at DESC);

-- ai_judgments: 종목 코드 기반 조회
CREATE INDEX IDX_AI_JUDGMENTS_STOCK_CODE
    ON ai_judgments (stock_code);

-- post_mortem_reports: ai_judgment_id FK 조회
CREATE INDEX IDX_POST_MORTEM_JUDGMENT
    ON post_mortem_reports (ai_judgment_id);

-- position_thresholds: ai_judgment_id FK 조회
CREATE INDEX IDX_POS_THRESH_JUDGMENT
    ON position_thresholds (ai_judgment_id);
