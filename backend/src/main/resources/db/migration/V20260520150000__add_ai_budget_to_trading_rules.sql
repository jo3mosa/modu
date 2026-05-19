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

-- DB 무결성: 애플리케이션 검증(@Min(1))을 우회하는 경로(직접 SQL/배치)에서도
-- 음수 진입을 차단해 hard rule 판단이 깨지지 않도록 보장.
-- 0 은 "미설정" 으로 허용(runtime skip 정책과 정합) — 따라서 >= 0.
ALTER TABLE trading_rules
    ADD CONSTRAINT trading_rules_ai_budget_amount_nonneg_chk
    CHECK (ai_budget_amount >= 0);
ALTER TABLE trading_rule_histories
    ADD CONSTRAINT trading_rule_histories_ai_budget_amount_nonneg_chk
    CHECK (ai_budget_amount >= 0);
