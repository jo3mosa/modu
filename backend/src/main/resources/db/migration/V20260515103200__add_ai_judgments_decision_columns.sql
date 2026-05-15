-- =====================================================================
-- ai_judgments — AI Signal Handler (S14P31B106-263) 보강
--   - decision_id      : AI 페이로드 고유 ID. AI 합의 명세상 멱등키이나 현재 ai_agent 가 미발행.
--                        향후 발행 시 사용 가능하도록 컬럼만 추가 (UNIQUE 없음)
--   - source_event_id  : AI 트리거 원본 이벤트 식별자. (user_id, source_event_id) 조합으로 BE 멱등키 역할
--   - execution_status : BE 자체 판정 상태 — READY/APPROVAL_REQUIRED/HOLD_ONLY/BLOCKED
-- =====================================================================

ALTER TABLE ai_judgments
    ADD COLUMN decision_id      VARCHAR     NULL,
    ADD COLUMN source_event_id  VARCHAR     NULL,
    ADD COLUMN execution_status VARCHAR(20) NULL;


-- =====================================================================
-- CHECK CONSTRAINT — execution_status 허용 값 4종
-- =====================================================================
ALTER TABLE ai_judgments
    ADD CONSTRAINT CHK_AI_JUDGMENTS_EXECUTION_STATUS
        CHECK (execution_status IS NULL OR execution_status IN
               ('READY', 'APPROVAL_REQUIRED', 'HOLD_ONLY', 'BLOCKED'));


-- =====================================================================
-- PARTIAL UNIQUE INDEX — (user_id, source_event_id) 멱등키
--   AI 재시도 시 같은 트리거가 재발행되더라도 ai_judgments 중복 INSERT 차단
--   기존 row 의 source_event_id 가 NULL 이므로 partial 로 NULL 제외
-- =====================================================================
CREATE UNIQUE INDEX UQ_AI_JUDGMENTS_USER_SOURCE_EVENT
    ON ai_judgments (user_id, source_event_id)
    WHERE source_event_id IS NOT NULL;
