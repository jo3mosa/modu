# MODU AI Agent

MODU의 AI Agent 모듈은 분석 서버가 감지한 시장 신호를 사용자별 투자 판단으로 변환하는 **LangGraph 기반 Multi-Agent 파이프라인**입니다.

이 모듈은 주문을 직접 실행하지 않습니다. AI Agent는 판단과 근거를 생성해 Kafka로 발행하고, 실제 주문 row 생성, KIS API 호출, 체결 처리, 판단 저장은 백엔드가 담당합니다.

---

## 시스템 안에서의 위치

```text
analysis_server
  |
  | Kafka: market.signal.detected
  v
ai_agent
  - 시장 신호 수신
  - 보유자/매수 후보 사용자 fanout
  - 사용자별 LangGraph 실행
  - Agent 발화 메시지 발행
  - 최종 판단 발행
  |
  | Kafka: ai.decision.generated
  v
backend
  - AI 판단 저장
  - 승인 대기/승인/거부 처리
  - 주문 row 생성
  - KIS 주문/체결 처리
```

피드백 흐름은 별도 consumer로 분리되어 있습니다.

```text
backend
  |
  | Kafka: trade.settled
  v
ai_agent feedback consumer
  - ai_judgments에서 당시 판단 근거 조회
  - Postmortem Agent 회고 생성
  - post_mortem_reports 저장
```

---

## 운영 진입점

### 판단 Consumer

[app/consumer.py](./app/consumer.py)

운영 기본 진입점입니다. 두 개의 consumer thread를 실행합니다.

| Consumer | 입력 토픽 | 처리 | 출력 |
| --- | --- | --- | --- |
| `market-signal-consumer` | `market.signal.detected` | `MarketTriggerEvent`를 사용자별 `UserTriggerEvent`로 변환 | `ai.trigger.requested` |
| `user-trigger-consumer` | `ai.trigger.requested` | LangGraph 실행 후 판단 payload 생성 | `ai.decision.generated` |

스레드가 예외로 종료되면 프로세스를 종료해 컨테이너/Kubernetes 재시작을 유도합니다.

### 회고 Consumer

[app/feedback/consumer.py](./app/feedback/consumer.py)

`trade.settled` 이벤트를 받아 사후 회고를 생성합니다. 판단 파이프라인과 장애/자원 영향을 분리하기 위해 별도 entry point로 구성되어 있습니다.

---

## Kafka 토픽

| 토픽 | 생산자 | 소비자 | 역할 |
| --- | --- | --- | --- |
| `market.signal.detected` | analysis_server | ai_agent | 종목 단위 시장 신호 |
| `ai.trigger.requested` | ai_agent | ai_agent | 시장 신호를 사용자별 실행 요청으로 fanout |
| `ai.decision.generated` | ai_agent | backend | AI 최종 판단 payload |
| `ai.agent.message` | ai_agent | backend | Agent 발화 메시지 저장 및 SSE 전달 |
| `trade.settled` | backend | ai_agent feedback | 매수→매도 청산 후 postmortem 트리거 |

---

## 폴더 구조

```text
ai_agent/
├── app/
│   ├── consumer.py                 # 판단 파이프라인 Kafka consumer
│   ├── config/
│   │   ├── kafka.py                # Kafka topic, producer, consumer
│   │   ├── llm.py                  # 모델 provider 선택 및 LLM factory
│   │   └── prompts/                # Agent별 prompt
│   ├── triggers/
│   │   ├── schemas.py              # MarketTriggerEvent, UserTriggerEvent
│   │   ├── user_trigger_matcher.py # 시장 이벤트 → 사용자별 trigger 변환
│   │   └── state_factory.py        # UserTriggerEvent → InvestmentAgentState
│   ├── graph/
│   │   ├── builder.py              # LangGraph 노드/엣지 정의
│   │   └── runner.py               # graph.invoke + Kafka publish
│   ├── agents/
│   │   ├── strategy/               # Bull/Bear + Strategy Manager
│   │   ├── decision/               # Decision Manager + Risk Gate
│   │   └── feedback/               # Postmortem Agent
│   ├── context/                    # LLM-free 컨텍스트 로딩
│   ├── memory/                     # 과거 판단/회고 조회 및 저장 adapter
│   ├── repositories/               # Redis 기반 포지션/가격/매수 후보 조회
│   ├── feedback/                   # trade.settled 회고 파이프라인
│   ├── state/                      # LangGraph state, Pydantic output schema
│   └── utils/                      # prompt/json/object/agent message helper
├── backtest/                       # 운영 그래프를 재사용하는 백테스트 모듈
├── dashboards/                     # 백테스트 결과 Streamlit viewer
└── tests/                          # 단위 테스트
```

---

## 판단 파이프라인

### 1. 시장 이벤트 수신

분석 서버는 종목 단위로 `MarketTriggerEvent`를 발행합니다.

주요 필드:

- `stock_code`
- `timestamp`
- `trigger.rule_ids`
- `trigger.trigger_reason`
- `analysis_snapshot`

`analysis_snapshot`에는 기술적 지표, 재무 데이터, 이벤트, 감성 점수, 뉴스 요약 등이 들어올 수 있습니다.

### 2. 사용자별 trigger fanout

[app/triggers/user_trigger_matcher.py](./app/triggers/user_trigger_matcher.py)

`match_market_event_to_users`는 시장 이벤트를 사용자별 `UserTriggerEvent`로 변환합니다.

처리 기준:

- 해당 종목 보유자 조회: `PositionIndexRepository`
- 사용자 포트폴리오 스냅샷 조회: `PortfolioSnapshotRepository`
- 현재가 조회: `MarketPriceRepository`
- 비보유 매수 후보 조회: `BuyCandidateRepository`

보유자는 항상 `is_holder=True`로 처리됩니다. 비보유자는 종목 risk tier와 사용자 risk grade가 맞는 경우에만 포함됩니다.

비보유자 이벤트는 최종 결과가 BUY일 때만 백엔드로 발행됩니다. SELL/HOLD는 비보유자에게 의미가 없으므로 [app/graph/runner.py](./app/graph/runner.py)에서 발행을 생략합니다.

### 3. LangGraph State 생성

[app/triggers/state_factory.py](./app/triggers/state_factory.py)

`UserTriggerEvent`는 `InvestmentAgentState`로 변환됩니다.

```text
user_id
as_of
analysis_snapshot
candidate_assets = [{"stock_code": event.stock_code}]
portfolio_snapshot
```

trigger의 rule id/reason은 현재 state의 별도 필드로 저장하지 않고, `analysis_snapshot`과 Kafka payload 추적 정보로 활용합니다.

---

## LangGraph 구조

[app/graph/builder.py](./app/graph/builder.py)

운영 기본 모드는 `debate_1`입니다.

```text
context_loader
  -> bull_researcher
  -> bear_researcher
  -> decision_manager
  -> strategy_manager
  -> risk_gate
  -> END
```

`strategy_manager`가 `flow_status="hold"`를 반환하면 `risk_gate`를 거치지 않고 종료합니다.

### Debate Mode

| Mode | 토론 라운드 | 그래프 흐름 |
| --- | --- | --- |
| `debate_0` | 0회 | `context_loader -> decision_manager -> strategy_manager -> risk_gate` |
| `debate_1` | 1회 | `context_loader -> bull -> bear -> decision_manager -> strategy_manager -> risk_gate` |
| `debate_2` | 2회 | `bull -> bear`를 2회 반복한 뒤 `decision_manager`로 이동 |

`debate_0`은 `decision_manager_solo.txt` 프롬프트를 사용합니다. Bull/Bear 발언이 없는 상태에서 토론 부재를 이유로 hold가 과도하게 나오지 않도록 별도 프롬프트를 둔 구조입니다.

---

## 노드 역할

| 노드 | 역할 | LLM |
| --- | --- | --- |
| `context_loader` | 사용자 성향, 자동매매 정책, 과거 판단 memory 로드 | No |
| `bull_researcher` | 매수/상승 관점의 자유 텍스트 발언 생성 | Yes |
| `bear_researcher` | 매도/리스크 관점의 자유 텍스트 발언 생성 | Yes |
| `decision_manager` | Bull/Bear 토론 또는 signal을 종합해 `ResearchVerdict` 생성 | Yes |
| `strategy_manager` | `ResearchVerdict`를 주문 가능한 `FinalDecision`으로 변환 | Yes |
| `risk_gate` | 최종 판단의 형식, 자동매매 정책, 사용자 한도 검증 | No |

### Debate State

Bull/Bear 발언은 `investment_debate_state`에 누적됩니다.

주요 키:

- `history`: Bull/Bear 발언을 시간순으로 합친 문자열
- `bull_history`: Bull 발언 목록
- `bear_history`: Bear 발언 목록
- `debate_rounds`: `{round, bull, bear}` 단위 라운드 목록
- `latest_bull_argument`
- `latest_bear_argument`
- `round_count`

`decision_manager`는 `history`를 보고 토론 흐름을 종합하고, `strategy_manager`는 `debate_rounds`를 라운드 단위 근거로 사용합니다.

---

## Context Loader

[app/context/context_loader.py](./app/context/context_loader.py)

`context_loader`는 LLM을 호출하지 않는 결정론적 데이터 수집 레이어입니다.

수집하는 컨텍스트:

- `user_context`
  - 투자 성향
  - 손절률/익절률
  - AI 운용 한도 값이 제공되는 경우 단일 주문 한도 검증에 사용
  - 국내 주식 위험 정책
- `policy_context`
  - 자동매매 상태
  - kill switch 여부
  - 공통 거래 제한 정책
- `memory_context`
  - 최근 유사 판단
  - 최근 손실 판단
  - postmortem lessons
  - 유사 판단 요약 table
- `history_context`
  - 현재는 stub

DB 연결은 `DATABASE_URL` 또는 `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` 환경변수를 사용합니다.

---

## Memory 구조

실시간 판단 파이프라인은 판단 결과를 직접 DB에 저장하지 않습니다. 판단 저장은 `ai.decision.generated`를 받은 백엔드가 수행합니다.

AI Agent의 memory layer는 주로 다음 역할을 합니다.

- 과거 `ai_judgments` 및 `post_mortem_reports` 조회
- 최근 유사 판단과 손실 판단 요약
- postmortem lessons를 다음 판단 프롬프트에 주입
- feedback 파이프라인에서 postmortem report 저장

`memory_context`는 원본 목록을 그대로 LLM에 넘기지 않고 다음 4개 핵심 필드로 압축해 Agent 프롬프트에 전달합니다.

- `lessons_aggregate`
- `loss_pattern_brief`
- `similar_decisions_table`
- `recent_post_mortems`

---

## Output Schema

[app/state/schemas.py](./app/state/schemas.py)

### ResearchVerdict

`decision_manager`의 구조화 출력입니다.

주요 필드:

- `winning_side`: `bull`, `bear`, `balanced`
- `asset`
- `recommended_side`: `buy`, `sell`, `hold`
- `rationale`
- `key_bull_points`
- `key_bear_points`
- `confidence`
- `order_amount`
- `target_price`
- `stop_loss_price`

`recommended_side="hold"`일 때는 `order_amount=0`, `target_price=None`, `stop_loss_price=None`이어야 합니다.

### FinalDecision

`strategy_manager`의 구조화 출력입니다.

주요 필드:

- `action`: `trade`, `hold`
- `asset`
- `side`: `buy`, `sell`
- `order_amount`
- `target_price`
- `stop_loss_price`
- `reason_summary`
- `risk_summary`
- `expected_scenario`
- `confidence`
- `risk_level`: `low`, `medium`, `high`
- `user_message`

`action="trade"`인데 주문 필수 필드가 누락되면 `strategy_manager`가 hold로 강등합니다.

---

## Risk Gate

[app/agents/decision/risk_gate.py](./app/agents/decision/risk_gate.py)

`risk_gate`는 LLM을 호출하지 않습니다. AI 판단의 1차 hard rule 검증 레이어입니다.

검증 항목:

- `final_decision` 존재 여부
- `action`, `asset`, `side`, `order_amount` 기본값
- 자동매매 허용 여부: `policy_context.allow_auto_trade`
- BUY 손절 정합성: 사용자 손절률 대비 AI 손절가
- SELL 익절 정합성: 평단가와 사용자 익절률 대비 AI 목표가
- 단일 주문 AI 운용 한도: 값이 없으면 backward compatibility를 위해 skip

통과 시:

```text
risk_cleared = true
flow_status = completed
```

실패 시:

```text
flow_status = hold 또는 blocked
risk_check_result.checks에 상세 사유 기록
```

백엔드는 이후 실시간 데이터 기준으로 2차 hard rule을 수행합니다.

---

## Backend Payload

[app/graph/runner.py](./app/graph/runner.py)

LangGraph 결과는 `ai.decision.generated` payload로 변환됩니다.

주요 필드:

- `user_id`
- `source_event_id`
- `stock_code`
- `created_at`
- `final_decision`
- `debate.bull_claim`
- `debate.bear_claim`
- `debate.winner`
- `debate.key_signals`
- `indicators_snapshot`
- `flow_status`
- `is_holder`
- `stock_tier`
- `matched_risk_grade`

`final_decision`에서는 백엔드 매핑이 합의되지 않은 `asset`, `risk_summary`, `expected_scenario`, `user_message`를 제외하고 발행합니다. 종목 코드는 top-level `stock_code`를 사용합니다.

---

## Agent Message

[app/utils/agent_message.py](./app/utils/agent_message.py)

각 Agent 노드는 사용자 화면의 “AI 에이전트 회의실”을 위해 `ai.agent.message`를 발행합니다.

발행 Agent:

- `BULL`
- `BEAR`
- `DECIDE`
- `STRATEGY`

발행 실패는 판단 파이프라인을 중단시키지 않습니다. `DISABLE_AGENT_MESSAGE=1`이면 메시지 발행을 생략합니다. 백테스트처럼 Kafka가 없는 환경에서 불필요한 지연을 줄이기 위한 옵션입니다.

---

## Feedback / Postmortem

[app/feedback/pipeline.py](./app/feedback/pipeline.py)

백엔드는 매수→매도 거래가 청산되고 PnL이 확정되면 `trade.settled` 이벤트를 발행합니다.

`TradeSettledEvent` 주요 필드:

- `user_id`
- `ai_judgment_id`
- `trade_pnl_record_id`
- `raw_return`
- `alpha_return`
- `holding_days`

회고 파이프라인은 다음 순서로 동작합니다.

1. `ai_judgments`에서 당시 판단 사유, Bull/Bear 주장, winning side, risk grade, key signals 조회
2. `post_mortem_agent`에 판단 내용과 수익률 정보 전달
3. `PostMortemReflection` 생성
4. `post_mortem_reports`에 저장

회고 실패는 실시간 판단/주문 흐름에 영향을 주지 않도록 `None` 반환으로 흡수합니다.

---

## LLM 설정

[app/config/llm.py](./app/config/llm.py)

모델 이름 prefix로 provider를 선택합니다.

| Prefix | Provider | Key |
| --- | --- | --- |
| `claude*` | Anthropic | `CLAUDE_API_KEY` |
| `grok*` | xAI | `GROK_API_KEY` |
| 그 외 | OpenAI compatible SSAFY GMS proxy | `GMS_KEY` |

기본 모델은 `gpt-4o-mini`입니다.

환경변수:

- `DEBATE_MODEL`
- `STRATEGY_MODEL`
- `DECISION_MODEL`
- `STRUCTURED_MODEL`
- `DEBATE_TEMPERATURE`
- `DECISION_TEMPERATURE`
- `LLM_REQUEST_TIMEOUT`
- `LLM_MAX_RETRIES`

---

## 실패 정책

| 위치 | 실패 처리 |
| --- | --- |
| `bull_researcher` | fallback 발언을 남기고 다음 노드 진행 |
| `bear_researcher` | fallback 발언을 남기고 다음 노드 진행 |
| `decision_manager` | LLM/파싱 실패 시 hold verdict로 강등 |
| `strategy_manager` | LLM/파싱 실패 또는 주문 필드 누락 시 hold로 강등 |
| `risk_gate` | hard rule 위반 시 hold 또는 blocked |
| `run_and_publish` | 비보유자 SELL/HOLD 판단은 발행 생략 |
| `agent_message` | 발행 실패를 로깅하고 판단은 계속 진행 |
| `feedback consumer` | 잘못된 payload는 commit 후 skip, transient error는 retry |
| `post_mortem pipeline` | 결정 없음/LLM 실패/DB 실패 시 `None` 반환 |

---

## 지켜야 할 책임 경계

1. **AI Agent는 주문을 실행하지 않는다.**
   주문 생성과 KIS API 호출은 백엔드 책임입니다.

2. **`risk_gate`와 `context_loader`는 LLM을 호출하지 않는다.**
   안전 검증과 데이터 수집은 결정론적이어야 합니다.

3. **비보유자 추천 정책을 유지한다.**
   비보유자에게는 BUY 판단만 발행합니다.

4. **백엔드 payload 계약을 임의로 넓히지 않는다.**
   `ai.decision.generated` 필드는 백엔드 DTO와 저장 컬럼에 맞춰 유지해야 합니다.

5. **Agent 발화 실패가 판단 실패가 되면 안 된다.**
   `ai.agent.message`는 사용자 시각화용 부가 스트림입니다.

---

## 관련 문서

- [backtest/README.md](./backtest/README.md): 백테스트 실행 및 실험 모드
- [dashboards/README.md](./dashboards/README.md): 백테스트 결과 viewer
- [../README.md](../README.md): 전체 MODU 서비스 소개
