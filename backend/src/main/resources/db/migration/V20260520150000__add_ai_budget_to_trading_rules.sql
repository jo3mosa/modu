-- AI 운용 한도 (단일 주문 + 일일 누적) — 단위: KRW 정수.
-- AI risk_gate 1차 hard rule (단일 주문) + BE SignalHandlerService 2차 hard rule (누적) 의 기준값.
-- 0 = 미설정 → 양쪽 모두 검증 skip (backward compat).
ALTER TABLE trading_rules
    ADD COLUMN ai_budget_amount BIGINT NOT NULL DEFAULT 0;
ALTER TABLE trading_rule_histories
    ADD COLUMN ai_budget_amount BIGINT NOT NULL DEFAULT 0;

-- 기존 row 채움이 끝났으므로 DEFAULT 제거 — 신규 row 입력 시 명시적 값 요구.
ALTER TABLE trading_rules
    ALTER COLUMN ai_budget_amount DROP DEFAULT;
ALTER TABLE trading_rule_histories
    ALTER COLUMN ai_budget_amount DROP DEFAULT;
