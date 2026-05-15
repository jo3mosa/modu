-- =====================================================================
-- post_mortem_reports — Post-Mortem Agent 멱등 제약 (AI 팀 합의)
--
-- 배경:
--  ai_agent 의 trade.settled 컨슈머는 at-least-once 의미로 동작.
--  run_post_mortem 성공 후 Kafka offset commit. commit 실패/프로세스 사망 시
--  다음 poll 에서 재처리 → 같은 ai_judgment_id row 가 2건 생성될 수 있음.
--  회고는 retrieval 에서 후속 결정 컨텍스트로 사용되므로(memory/retrieval.py),
--  중복이 누적되면 LLM 입력 토큰이 부풀고 동일 교훈이 가중치 받게 됨.
--
-- 도메인 가정:
--  한 AI 판단(ai_judgments.id) 에 대한 회고는 1건만 존재.
--  매수→매도 cycle 종료 1회당 trade.settled 1회 발행이 backend 합의 사항.
--
-- 후속 (AI 측, 별도 PR):
--  memory_log.py 의 INSERT 를 ON CONFLICT (ai_judgment_id) DO NOTHING RETURNING id
--  로 교체. 충돌 시 RETURNING 이 빈 결과를 주므로 silent skip 처리.
-- =====================================================================

ALTER TABLE post_mortem_reports
    ADD CONSTRAINT UQ_POST_MORTEM_REPORTS_AI_JUDGMENT_ID
        UNIQUE (ai_judgment_id);
