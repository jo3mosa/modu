-- =====================================================================
-- 1. auto_trade_settings — auto_trade_status 허용 값 CHECK 제약
--    enum AutoTradeStatus 와 동기화: ACTIVE / INACTIVE / KILL_SWITCHED
--    Kill Switch 발동 시 reason / triggered_at 컬럼 (init 마이그레이션 시 신설됨) 사용
-- =====================================================================

ALTER TABLE auto_trade_settings
    ADD CONSTRAINT CHK_AUTO_TRADE_STATUS
        CHECK (auto_trade_status IN ('ACTIVE', 'INACTIVE', 'KILL_SWITCHED'));


-- =====================================================================
-- 2. ai_judgments.approval_expires_at — APPROVAL_REQUIRED 5분 만료 처리용
--    스케줄러 (1분 간격) 가 expires_at < NOW() 인 row 를 REJECTED 로 전환 + SSE 발송
--    READY/HOLD_ONLY/BLOCKED row 는 NULL (만료 무관)
-- =====================================================================

ALTER TABLE ai_judgments
    ADD COLUMN approval_expires_at TIMESTAMPTZ NULL;


-- =====================================================================
-- 3. 스케줄러 폴링 인덱스 — PARTIAL INDEX (만료 후보만)
--    execution_status = 'APPROVAL_REQUIRED' AND approval_expires_at IS NOT NULL
--    예상 row 수 적음 — 인덱스 작음
-- =====================================================================

CREATE INDEX IDX_AI_JUDGMENTS_APPROVAL_EXPIRES
    ON ai_judgments (approval_expires_at)
    WHERE execution_status = 'APPROVAL_REQUIRED' AND approval_expires_at IS NOT NULL;


-- =====================================================================
-- 4. ai_judgments.execution_status CHECK 제약 확장 — REJECTED, EXPIRED 추가
--    READY              : BUY/SELL 결정, 실행 대기
--    APPROVAL_REQUIRED  : BUY/SELL 결정, 사용자 승인 대기
--    HOLD_ONLY          : HOLD 결정, 기록만
--    BLOCKED            : 실행 불가 (잔고/한도/시장 마감/Kill Switch 등)
--    REJECTED           : 사용자가 명시적으로 승인 거부 (S14P31B106-292)
--    EXPIRED            : 5분 만료 후 스케줄러가 자동 전환 (S14P31B106-292)
-- =====================================================================

ALTER TABLE ai_judgments
    DROP CONSTRAINT CHK_AI_JUDGMENTS_EXECUTION_STATUS;

ALTER TABLE ai_judgments
    ADD CONSTRAINT CHK_AI_JUDGMENTS_EXECUTION_STATUS
        CHECK (execution_status IS NULL OR execution_status IN
               ('READY', 'APPROVAL_REQUIRED', 'HOLD_ONLY', 'BLOCKED', 'REJECTED', 'EXPIRED'));
