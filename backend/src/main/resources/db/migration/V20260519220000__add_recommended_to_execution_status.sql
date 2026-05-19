-- =====================================================================
-- ai_judgments.execution_status CHECK 제약 확장 — RECOMMENDED 추가
-- 관련 이슈: S14P31B106-354 (Risk Tier 비보유자 매수 추천)
--
-- 신규 값:
--   RECOMMENDED — 비보유자 BUY 추천. 자동매매 실행 X, 사용자 승인 시 매수 실행.
--                 APPROVAL_REQUIRED 와 동일 UI (Bell 모달) 에서 처리되지만
--                 FE 멘트만 다르게 표시 ("이 종목 사실래요?")
--
-- 기존 값 (V20260515174500 이후 7값):
--   READY / APPROVAL_REQUIRED / HOLD_ONLY / BLOCKED / REJECTED / EXPIRED
-- =====================================================================

ALTER TABLE ai_judgments
    DROP CONSTRAINT CHK_AI_JUDGMENTS_EXECUTION_STATUS;

ALTER TABLE ai_judgments
    ADD CONSTRAINT CHK_AI_JUDGMENTS_EXECUTION_STATUS
        CHECK (execution_status IS NULL OR execution_status IN
               ('READY', 'APPROVAL_REQUIRED', 'HOLD_ONLY', 'BLOCKED',
                'REJECTED', 'EXPIRED', 'RECOMMENDED'));
