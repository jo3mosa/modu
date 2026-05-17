-- =====================================================================
-- agent_messages — AI 에이전트 회의(채팅) 메시지 영속화
--
-- 한 AI 판단(ai_judgments 1 row) 은 여러 에이전트(BULL/BEAR/STRATEGY/DECIDE)
-- 의 발화로 구성된다. AI 파트(ai_agent Python)가 에이전트 호출이 끝날 때마다
-- ai.agent.message 토픽으로 1건씩 발행하면 BE 가 본 테이블에 INSERT 한다.
--
-- [SSE 와의 관계]
-- 본 테이블 INSERT 후 AFTER_COMMIT 단계에서 SSE "agent-message" 이벤트 publish.
-- 프론트는 채널 진입 시 본 테이블에서 과거 메시지를 페이지 조회, 이후 SSE 로 실시간 수신.
--
-- [멱등키]
-- (user_id, judgment_id, agent, seq) 조합으로 partial unique index.
-- AI 가 같은 발화를 재시도해도 중복 INSERT 차단. judgment_id NULL 케이스(자유 발화)
-- 는 멱등 보장 대상에서 제외.
-- =====================================================================

CREATE TABLE agent_messages
(
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    stock_code  VARCHAR(20)  NOT NULL,
    judgment_id BIGINT       NULL,
    agent       VARCHAR(20)  NOT NULL,
    seq         INT          NOT NULL DEFAULT 0,
    text        TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT FK_AGENT_MESSAGES_JUDGMENT
        FOREIGN KEY (judgment_id) REFERENCES ai_judgments (id) ON DELETE SET NULL,

    CONSTRAINT CHK_AGENT_MESSAGES_AGENT
        CHECK (agent IN ('BULL', 'BEAR', 'STRATEGY', 'DECIDE'))
);

-- 채널 진입 시 최신 N 개 조회용 (user_id + stock_code 필터 + created_at DESC 정렬)
CREATE INDEX IDX_AGENT_MESSAGES_USER_STOCK_TIME
    ON agent_messages (user_id, stock_code, created_at DESC);

-- 같은 판단 묶음 내 순서 조회용 (판단 상세 화면 등에서 사용)
CREATE INDEX IDX_AGENT_MESSAGES_JUDGMENT_SEQ
    ON agent_messages (judgment_id, seq)
    WHERE judgment_id IS NOT NULL;

-- AI 재시도 시 동일 (judgment, agent, seq) 조합 중복 INSERT 차단
CREATE UNIQUE INDEX UQ_AGENT_MESSAGES_USER_JUDGMENT_AGENT_SEQ
    ON agent_messages (user_id, judgment_id, agent, seq)
    WHERE judgment_id IS NOT NULL;
