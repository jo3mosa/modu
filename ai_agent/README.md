# MODU AI Agent

MODU 사용자별 매매 의사결정을 만드는 **LangGraph 기반 멀티 에이전트** 모듈.
한국 주식 시장의 분석 신호와 사용자 컨텍스트를 받아
Bull/Bear 토론 → 전략 종합 → 최종 결정 → 리스크 게이트까지
한 번의 그래프 호출로 처리한다.

> 이 문서는 **현재 코드(`app/graph/builder.py` 기준)** 를 정확히 반영한다.
> 코드를 변경하면 이 문서도 함께 갱신해 주세요. 한 번 어긋나면 신입에게 큰 비용이 됩니다.

---

## 1. 시스템 안에서의 위치

```
┌──────────────────┐  Kafka                ┌────────────────────┐  Kafka                 ┌──────────────────┐
│ Analysis Layer   │ ───────────────────▶  │  AI Agent (이 모듈) │ ────────────────────▶ │  Backend         │
│ (analysis_server)│ market.signal.detected│  사용자 매칭 + Graph│ ai.decision.generated │ (Spring Boot)    │
└──────────────────┘                       │   실행              │                        │ KIS 주문 실행    │
                                           └────────────────────┘                        └──────────────────┘
                                                  ▲       │
                                                  │       │ DB / Redis read
                                              사용자 컨텍스트 / 보유 종목 인덱스
```

- **입력**: `market.signal.detected` (Analysis Layer 발행, 종목 단위 신호)
- **처리**: 신호 → 보유 사용자 매칭 → 사용자별 LangGraph 1회 실행
- **출력**: `ai.decision.generated` (백엔드가 받아 실제 KIS 주문 실행)

이 모듈은 **결정만 만들고 결정만 발행**한다. 실제 KIS API 호출은 백엔드의 책임이며,
백엔드 `KisOrderConsumer`가 `ai.decision.generated`를 받아 주문을 집행하고,
체결 후 `trade.order.executed` 컨슈머에서 `ai_judgments.order_id`를 UPDATE 한다.
즉, 실행 결과는 ai_agent의 그래프 State가 아니라 DB 레벨에서 합류한다.

---

## 2. 폴더 구조

```
ai_agent/
├── main.py                              # 단일 실행 데모 (graph 1회 invoke)
├── requirements.txt
├── README.md
└── app/
    ├── consumer.py                      # 운영 진입점. Kafka 컨슈머 2개 스레드 기동
    │
    ├── config/
    │   ├── llm.py                       # ChatOpenAI 인스턴스 (SSAFY GMS gateway)
    │   ├── kafka.py                     # KafkaProducer/Consumer + topic 상수
    │   ├── redis.py                     # Redis 클라이언트 lazy singleton
    │   └── prompts/                     # 노드별 prompt 텍스트
    │       ├── bull_researcher.txt
    │       ├── bear_researcher.txt
    │       ├── strategy_manager.txt        # debate_1/2 — 토론 평가 모드
    │       ├── strategy_manager_solo.txt   # debate_0 — 토론 없이 signals 직접 해석
    │       ├── decision_manager.txt
    │       └── post_mortem_agent.txt       # SELL 체결 후 사후 회고 LLM
    │
    ├── triggers/                        # Kafka 이벤트 ↔ state 변환
    │   ├── schemas.py                   # MarketTriggerEvent / UserTriggerEvent
    │   ├── user_trigger_matcher.py      # MarketEvent → 보유 사용자별 UserTriggerEvent 분기
    │   ├── state_factory.py             # UserTriggerEvent → InvestmentAgentState
    │   ├── pipeline.py                  # mock 트리거로 graph end-to-end 검증용
    │   └── mock_trigger.py              # 개발/테스트용 가짜 트리거 생성
    │
    ├── repositories/
    │   └── position_index_repository.py # Redis Set: "이 종목 보유한 user_id 목록"
    │
    ├── state/
    │   ├── investment_state.py          # 그래프 공유 상태 (모든 노드의 척추)
    │   └── schemas.py                   # ResearchVerdict / StrategyDraft / FinalDecision
    │
    ├── context/                         # Reasoning 노드 입력 컨텍스트 수집 (LLM-free)
    │   ├── context_loader.py            # context_loader 노드. 아래 모듈 합성
    │   ├── user_context.py              # PostgreSQL → 투자성향/거래규칙/자동매매 정책
    │   └── memory_context.py            # MemoryStore → 과거 판단 이력 회상
    │
    ├── memory/                          # 과거 판단 저장/조회 (ai_judgments 등)
    │   ├── interfaces.py                # MemoryStore Protocol + 공통 Enum/TypedDict
    │   ├── db_store.py                  # MemoryStore의 DB 구현체
    │   ├── retrieval.py                 # SELECT 전담
    │   └── memory_log.py                # INSERT 전담 (decision / postmortem)
    │
    ├── agents/                          # LangGraph 노드 함수 (LLM 사용)
    │   ├── strategy/
    │   │   ├── bull_researcher.py       # 매수 옹호 발언 (자유 텍스트)
    │   │   ├── bear_researcher.py       # 매도/리스크 반박 (자유 텍스트)
    │   │   └── strategy_manager.py      # 토론 종합 → ResearchVerdict 구조화 출력
    │   ├── decision/
    │   │   ├── decision_manager.py      # ResearchVerdict → FinalDecision
    │   │   └── risk_gate.py             # ※LLM-free※ 결정의 형식·정책 검증 게이트
    │   └── feedback/
    │       └── post_mortem_agent.py     # SELL 체결 후 사후 회고 LLM 노드
    │
    ├── feedback/                        # SELL 결정 후 사후 회고 흐름 (MVP: cron 없음)
    │   ├── consumer.py                  # SELL 체결 알림 컨슈머
    │   ├── pipeline.py                  # 단일 회고 실행 (학습/디버깅용)
    │   └── schemas.py                   # PostMortem 입력/출력 스키마
    │
    ├── graph/
    │   ├── builder.py                   # LangGraph 정의 (노드 / 엣지 / 조건부 분기)
    │   └── runner.py                    # graph.invoke + Kafka publish 래퍼
    │
    ├── observability/
    │   └── langsmith_helpers.py         # LangSmith metadata 태깅 (운영 차단 X)
    │
    ├── knowledge_base/                  # 정책/지식 마크다운 (참고용)
    │   ├── investment-profile.md
    │   ├── investment-strategy.md
    │   ├── llm-wiki.md
    │   └── trade-history-wiki.md
    │
    └── utils/
        ├── prompt_loader.py             # [SYSTEM]/[HUMAN] 구분자 기반 프롬프트 로더 (lru_cache)
        ├── agent_message.py             # 그래프 노드 발화를 외부로 publish (실시간 시각화용)
        ├── json_utils.py                # Pydantic/dict 통합 JSON 직렬화
        └── object_utils.py              # dict + Pydantic 통합 get_value

backtest/                                # ※ 별도 모듈 ※ 자세한 가이드는 backtest/README.md
├── run_ai_backtest.py                   # AI 백테스트 진입점 (debate_* / random / mock 모드)
├── event_loop.py                        # 거래일 단위 시뮬레이션 루프
├── scoring.py                           # raw_return + post_mortem 통합 채점
├── data_sources.py                      # Postgres(시장·지표·재무) + Mongo(공시·뉴스) 조회
├── signal_generator.py                  # Analysis Layer 신호 생성 (백테스트 시점 재현)
└── adapters/graph_decision.py           # 운영 그래프와 동일한 LangGraph를 백테스트에서 호출

dashboards/                              # Streamlit 백테스트 결과 뷰어
└── backtest_viewer.py
```

---

## 3. LangGraph 그래프

`app/graph/builder.py` 의 정의:

```
              ┌──────────────────┐
              │  context_loader  │  user/policy/memory/history 컨텍스트 로드 (DB+메모리)
              └────────┬─────────┘
                       ▼
              ┌──────────────────┐
              │ bull_researcher  │  매수 옹호 발언 → debate_state.history
              └────────┬─────────┘
                       ▼
              ┌──────────────────┐
              │ bear_researcher  │  매도/리스크 반박 → debate_state.history
              └────────┬─────────┘
                       ▼
              ┌──────────────────┐
              │ strategy_manager │  토론 종합 → ResearchVerdict + StrategyDraft
              └────────┬─────────┘
                       ▼
              ┌──────────────────┐
              │ decision_manager │  FinalDecision 생성 (action / 사이즈 / 시나리오 / risk_level)
              └────────┬─────────┘
                       │
        flow_status == "hold"      flow_status != "hold"
                       │                  │
                       ▼                  ▼
                      END        ┌──────────────────┐
                                 │    risk_gate     │  형식·정책 검증 (LLM-free)
                                 └────────┬─────────┘
                                          ▼
                                         END
                                  (risk_cleared 값과 무관하게 그래프는 종료되며,
                                   결과는 ai.decision.generated 로 백엔드에 발행)
```

### 노드 역할 요약

| 노드 | 입력 | 출력 (state 갱신) | LLM |
|---|---|---|---|
| `context_loader` | `user_id`, `analysis_snapshot`, `candidate_assets` | `user_context`, `policy_context`, `memory_context` (8개 키 — §3.5 참조), `history_context` | ❌ |
| `bull_researcher` | analysis signals + 컨텍스트 + `memory_*` 4섹션 + 직전 Bear 발언 | `investment_debate_state` (Bull 발언 누적) | ✅ |
| `bear_researcher` | 위 + 직전 Bull 발언 | `investment_debate_state` (Bear 발언 누적, `round_count` 증가) | ✅ |
| `strategy_manager` | debate history + 컨텍스트 + `memory_*` 4섹션 | `research_verdict`, `strategy_draft` (실패 시 hold 강등) | ✅ |
| `decision_manager` | `research_verdict` + 전체 컨텍스트 + `memory_*` 4섹션 | `final_decision`, `flow_status` | ✅ |
| `risk_gate` | `final_decision`, `policy_context.allow_auto_trade` | `risk_cleared`, `risk_check_result`, `flow_status` | ❌ |

> **노드는 `state.memory_context`를 통째로 LLM에 넘기지 않는다.** 정제된 4섹션
> (`lessons_aggregate` / `loss_pattern_brief` / `similar_decisions_table` /
> `recent_post_mortems`)을 별도 프롬프트 변수로 분리 주입해 LLM 주의를 집중시킨다.
> 자세한 정제 함수와 회상 루프는 §3.5 참조.

### 두 개의 hard rule (절대 깨지 않는 가정)

1. **ai_agent는 주문을 실행하지 않는다.** 그래프는 결정 발행에서 끝나며,
   실주문은 백엔드 `KisOrderConsumer`의 책임이다. ai_agent에 executor 노드/`action/*` 레이어를 다시 도입하지 말 것.
2. **`risk_gate`, `context/*` 는 LLM을 호출하지 않는다.**
   결정의 비결정성을 차단하는 안전 레이어이며, LLM 도입은 안전 가정을 깬다.

---

## 3.5. Reflection 메모리 루프 (사후 분석 기반 성장형)

본 시스템은 **LLM 모델을 재학습(fine-tuning)하지 않는다**. 대신 매 거래의
결정-결과 쌍을 LLM이 자체 회고(post-mortem)하고, 그 회고를 다음 결정의
프롬프트 컨텍스트로 회상해 의사결정 품질을 점진 강화하는 **닫힌 루프**를 운용한다.

> 참고: TradingAgents (UCLA, 2024) 논문의 `FinancialSituationMemory` 패턴을
> 한국 주식·실거래 환경에 맞춰 **정형 SQL 기반으로 단순화**한 구현
> (벡터 검색 대신 stock_code/sector/key_signals AND/OR 매칭 — 사용자×종목 격리·
> 백테스트 재현성·운영 인프라 비용을 위해 의도적 단순화).

### 데이터 흐름 (닫힌 루프)

```
┌─────────────────────────────────────────────┐
│ 1. Decision Manager → final_decision       │
│    runner.run_and_publish → BE에 발행        │
│    BE가 ai_judgments INSERT + KIS 주문 실행  │
└────────────────────┬────────────────────────┘
                     ▼
              (보유 → 매도 → 청산)
                     ▼
┌─────────────────────────────────────────────┐
│ 2. BE: trade.settled Kafka 이벤트 발행       │
│    feedback consumer (app/feedback/) 수신    │
└────────────────────┬────────────────────────┘
                     ▼
┌─────────────────────────────────────────────┐
│ 3. post_mortem_agent                        │
│    decision_content + raw_return            │
│    + alpha_return + holding_days를 LLM에 입력│
│    → PostMortemReflection (Pydantic 구조화)  │
│    → post_mortem_reports DB INSERT          │
└────────────────────┬────────────────────────┘
                     ▼
┌─────────────────────────────────────────────┐
│ 4. 다음 거래 트리거 → context_loader         │
│    DecisionRetrieval로 유사 판단 회상        │
│    (LATERAL JOIN으로 post_mortem 최신 1건)   │
│    → memory_context 4섹션 정제 → state 저장  │
└────────────────────┬────────────────────────┘
                     ▼
       Bull/Bear/Strategy/Decision LLM에
       4섹션 프롬프트 변수로 분리 주입
```

### `memory_context` 8개 키 (4 정제 + 4 raw)

`load_memory_context()`가 반환하는 dict 구조:

| 키 | 출처 | 용도 |
|---|---|---|
| **`lessons_aggregate`** | `post_mortem_reports.lessons` 빈도 가중 top-8 | **"반드시 우선 검토"** — 회고에서 추출된 룰 |
| **`loss_pattern_brief`** | 손실 판단의 `judgment_reason` + `bear_claim` 한 줄/건 압축 | 반복 실수 회피용 — Bear 주장의 핵심 근거 |
| **`similar_decisions_table`** | 유사 결정 요약 (정형 메타 제거, `reason` 120자 컷) | 정형 의사결정 표 |
| **`recent_post_mortems`** | `summary` + `lessons`가 모두 있는 회고만 top-5 | Bear/Decision Manager의 1급 근거 |
| `query_basis` | 회상 매칭 기준 (stock_codes/sectors/key_signals) | 디버깅·트레이스 |
| `recent_decisions` | retrieval raw 결과 (PastDecision dict 10건) | 디버깅·향후 벡터 검색 도입 호환 |
| `recent_loss_decisions` | `only_loss=True` raw 결과 5건 | 디버깅 |
| `summary` | 회상 건수 카운트 문자열 | 한 줄 요약 |

> **정제 4종은 모두 결정론적 Python 함수** (`_aggregate_lessons` /
> `_summarize_loss_pattern` / `_brief_table` / `_recent_post_mortems`).
> LLM 호출이 추가로 발생하지 않아 **백테스트 재현성을 깨지 않는다**.

### 회상 매칭 로직

`DecisionRetrieval` (`app/memory/retrieval.py`) — PostgreSQL 정형 SQL:

```
WHERE  aj.user_id = :user_id
  AND  aj.judged_at >= NOW() - :days * INTERVAL '1 day'
  AND  (aj.stock_code = ANY(:stock_codes))   -- 그룹 간 AND, 그룹 내 OR
  AND  (aj.sector     = ANY(:sectors))
  AND  EXISTS(jsonb_array_elements_text(aj.key_signals) = ANY(:key_signals))
LEFT JOIN LATERAL (
    SELECT lessons, summary FROM post_mortem_reports
    WHERE ai_judgment_id = aj.id ORDER BY created_at DESC LIMIT 1
) pmr ON TRUE
```

- **사용자×종목 격리**: `WHERE aj.user_id`로 자연스럽게 만족 (벡터 검색 시 추가 필터 필요)
- **재현성**: `as_of` 파라미터로 백테스트 시점 기준 회상 가능 — LLM 결정성과 SQL 결정성을 모두 보장

### 운영 가동 상태 (현재)

| 단계 | 구현 | 운영 가동 |
|---|---|---|
| 1. 결정 발행 + ai_judgments 적재 | ✅ | ✅ |
| 2. trade.settled Kafka 발행 | (BE 측) | ⚠️ BE 발행 합의/구현 진행 중 — 토픽 비어 있으면 컨슈머 polling 대기 |
| 3. post_mortem_agent 회고 생성 + DB 영속 | ✅ | (2번 가동 후 동작) |
| 4. 다음 결정 시 회상 + memory_context 4섹션 주입 | ✅ | ✅ (회상할 회고가 0건이면 빈 배열로 안전 fallback) |

---

## 4. State (`InvestmentAgentState`)

모든 노드는 **단일 Pydantic 모델**(`app/state/investment_state.py`)을 읽고 자기 담당 필드만 갱신한다.

| 영역 | 필드 | 누가 채우나 |
|---|---|---|
| 실행 주체 | `user_id` | 트리거 단계 (`state_factory`) |
| 시장/분석 입력 | `analysis_snapshot` | Analysis Layer (Kafka, `trigger` nested + 4분할 signals) |
| 후보 종목 | `candidate_assets` | `state_factory` (Analysis Layer 메시지의 stock_code 기반 자체 생성) |
| 포트폴리오 | `portfolio_snapshot` | 그래프 실행 전 백엔드 주입 — **Risk Gate가 broker API를 직접 호출하지 않는 정책** |
| 컨텍스트 | `user_context`, `policy_context`, `memory_context` (8개 키 — §3.5), `history_context` | `context_loader` |
| 토론 | `investment_debate_state` (`history`/`bull_history`/`bear_history`/`current_response`/`count`) | bull/bear/strategy_manager |
| Reasoning 결과 | `research_verdict`, `strategy_draft`, `final_decision` | strategy/decision manager |
| 리스크 | `risk_check_result`, `risk_cleared` | risk_gate |
| 제어 | `flow_status` (`running`/`hold`/`blocked`/`completed`/`failed`), `error_context` | 모든 노드 |
| 사후 | `later_market_data`, `postmortem_report` | (별도 Feedback graph / 백엔드 — 현재 MVP 범위 밖) |

> 실행 결과(주문 ID/체결가 등)는 ai_agent State에 들어오지 않는다. 백엔드가 `trade.order.executed` 컨슈머에서 `ai_judgments.order_id`를 UPDATE 하는 흐름으로 합류한다.

> 실행 결과(주문 ID/체결가 등)는 ai_agent State에 들어오지 않는다. 백엔드가 `trade.order.executed` 컨슈머에서 `ai_judgments.order_id`를 UPDATE 하는 흐름으로 합류한다.

### 핵심 Pydantic 스키마 (`app/state/schemas.py`)

- **`ResearchVerdict`** — Strategy Manager 출력. `winning_side`, `recommended_side`, `target_price`, `stop_loss_price`, `order_amount`, `confidence` 포함. `recommended_side != hold` 일 때 가격/금액 필드 필수 (`@model_validator`).
- **`StrategyDraft`** — Verdict를 후속 단계 계약으로 옮긴 단순화 본.
- **`FinalDecision`** — Decision Manager 출력. `action ∈ {trade, hold}`, `risk_level ∈ {low, medium, high}`, `expected_scenario` (base/bear/bull) 포함. `action="trade"` 인데 주문 필수 필드 누락 시 hold로 강등.

---

## 5. 두 가지 실행 모드

### 5-1. 단일 호출 (학습/디버깅)

`pipeline.py` 가 **mock UserTriggerEvent → graph 끝까지** 검증한다. 이게 가장 안전한 학습용 실행 경로다:

```bash
python -m app.triggers.pipeline
```

> ⚠️ `python main.py` 도 존재하지만, `main.py` 의 mock state 에 `user_id` 가 비어 있어
> 현재 `context_loader` 에서 `ValueError` 가 난다. 학습용으로는 `pipeline.py` 를 쓰거나,
> `main.py` 의 `InvestmentAgentState(...)` 에 `user_id=1` 을 직접 추가해서 돌리면 된다.

### 5-2. 운영 모드 (Kafka 컨슈머)

```bash
python -m app.consumer
```

`app/consumer.py` 는 두 개의 컨슈머 스레드를 띄운다:

| 스레드 | 입력 토픽 | 처리 | 출력 토픽 |
|---|---|---|---|
| `market-signal-consumer` | `market.signal.detected` | `match_market_event_to_users` 로 보유 사용자별 분기 | `ai.trigger.requested` |
| `user-trigger-consumer` | `ai.trigger.requested` | `run_and_publish` (graph 실행 + 페이로드 빌더 `_build_decision_payload`) | `ai.decision.generated` |

분리 이유: 사용자 매칭은 가볍고, LLM 실행은 무겁다. 백프레셔/재시도/스로틀을 단계별로 독립 관리하기 위함.

**출력 페이로드 (`ai.decision.generated`, BE 합의)**

`app/graph/runner.py:_build_decision_payload` 가 구성한다:

```json
{
  "user_id": 1,
  "source_event_id": "market_event_abc123",
  "stock_code": "005930",
  "created_at": "2026-05-14T02:07:00+09:00",
  "final_decision": {
    "action": "trade",
    "side": "buy",
    "order_amount": 500000,
    "target_price": 75000,
    "stop_loss_price": 67000,
    "reason_summary": "...",
    "confidence": 0.78,
    "risk_level": "low"
  },
  "debate": {
    "bull_claim": "...",
    "bear_claim": "...",
    "winner": "bull",
    "key_signals": ["technical_signal", "sentiment_signal"]
  },
  "indicators_snapshot": { "technical": {...}, "fundamental": {...} },
  "flow_status": "completed"
}
```

- `source_event_id`: 입력 트리거 식별자. BE 컨슈머는 `(user_id, source_event_id)` 조합으로 멱등 처리 (같은 트리거 재수신 시 INSERT 무시).
- `created_at`: ai_agent 발행 시각(KST). BE 측 `ai_judgments.judged_at`에 매핑.
- `final_decision`: `FinalDecision.model_dump()` 결과에서 `asset / risk_summary / expected_scenario / user_message`는 BE 매핑 컬럼이 없어 명시 `exclude`.
- `debate.bull_claim` / `bear_claim`: 그래프 토론 상태(`investment_debate_state`)의 누적 발언 텍스트.
- `debate.winner`: `research_verdict.winning_side`.
- `debate.key_signals`: `extract_key_signals(analysis_snapshot)` 결과.
- `indicators_snapshot`: 분석 서버가 보낸 `analysis_snapshot.signals` 원본을 그대로 forwarding (BE는 `ai_judgments.indicators_snapshot` JSONB로 저장).

### 5-3. 회고 컨슈머 (별도 프로세스)

매도 체결 후 사후 회고를 실행하는 컨슈머는 **결정 컨슈머와 분리된 별도 entry point**다:

```bash
python -m app.feedback.consumer
```

| 프로세스 | 입력 토픽 | 처리 | 출력 |
|---|---|---|---|
| `trade-settled-consumer` | `trade.settled` | `run_post_mortem` (`app/feedback/pipeline.py`) — 과거 결정 컨텍스트 조회 + Post-Mortem LLM 실행 | DB INSERT (`post_mortem_reports`) |

분리 이유: 회고는 비동기/지연 허용 영역이라 결정 컨슈머와 자원·장애를 격리한다 (k8s 배포 시 별도 Pod 권장). 컨슈머 그룹은 `ai-agent-trade-settled`.

> 현재 `trade.settled` 토픽은 BE 측 발행이 합의/구현 진행 중이며, 토픽이 비어 있으면 컨슈머는 polling 상태로 대기한다.

---

## 6. 외부 의존성 / 환경 변수

| 시스템 | 사용처 | 환경 변수 |
|---|---|---|
| **PostgreSQL** | `context_loader` (사용자 정책), `memory/*` (판단 저장/조회) | `DATABASE_URL` |
| **Redis** | `position_index_repository` (보유 종목 인덱스) | `REDIS_HOST`, `REDIS_PORT`, `REDIS_DB`, `REDIS_PASSWORD` |
| **Kafka** | 입력/출력 토픽 | `KAFKA_BOOTSTRAP_SERVERS` |
| **OpenAI (SSAFY GMS gateway)** | 모든 LLM 노드 | `GMS_KEY`, `OPENAI_MODEL` (선택) |
| **LangSmith** (선택) | 그래프 trace + metadata | `LANGCHAIN_TRACING_V2`, `LANGCHAIN_API_KEY`, `LANGCHAIN_PROJECT` |

`.env` 는 `ai_agent/.env` 위치에서 로드된다 (`python-dotenv`).
모듈 import 시점에는 외부 시스템 연결을 강제하지 않으므로, Redis/Kafka 가 꺼져 있어도 import 자체는 실패하지 않는다.

---

## 7. 셋업 & 실행

```bash
cd ai_agent
python -m venv .venv
.\.venv\Scripts\activate          # macOS/Linux: source .venv/bin/activate
pip install -r requirements.txt
# ai_agent/.env 작성 (위 환경 변수 채우기)
python -m app.triggers.pipeline   # 단일 호출 (학습용)
# 또는
python -m app.consumer             # 운영 모드 (Kafka 필요)
```

운영 모드 진입 전에는 Redis 에 종목 보유 인덱스가 채워져 있어야 사용자 매칭이 동작한다. 예시:

```
SADD position:index:stock:005930 1 2
```

---

## 8. 테스트

```bash
pytest
```

현재 작성된 테스트:

- `tests/triggers/test_user_trigger_matcher.py`
- `tests/triggers/test_pipeline.py`
- `tests/repositories/test_position_index_repository.py`

LLM 노드(bull/bear/strategy_manager/decision_manager) 단위 테스트는 아직 빈 스텁이다. 추가 작업 영역.

---

## 9. 신입 가이드: 어디부터 봐야 하나

이 순서대로 읽으면 30분~1시간 안에 시스템 전체가 잡힌다:

1. `app/state/investment_state.py` — 시스템의 척추. 이걸 모르면 노드 코드를 못 읽는다.
2. `app/state/schemas.py` — `ResearchVerdict`, `StrategyDraft`, `FinalDecision` 계약.
3. `app/graph/builder.py` — 그래프 골격 (이 README 의 그림과 1:1 대응).
4. `app/agents/strategy/*` 한 세트 → `app/agents/decision/*` 한 세트.
5. `app/config/prompts/*.txt` — 각 LLM 노드의 system/human 프롬프트.
   특히 `[메모리 — *]` 4섹션 구조와 우선순위 지침을 확인 (§3.5).
6. `app/context/memory_context.py` — `load_memory_context()`와 정제 함수 4종
   (`_aggregate_lessons` / `_summarize_loss_pattern` / `_brief_table` / `_recent_post_mortems`).
   Reflection 루프의 핵심.
7. `app/agents/feedback/post_mortem_agent.py` + `app/feedback/consumer.py` — 회고 생성·영속화.
8. `app/triggers/state_factory.py` + `app/consumer.py` — 운영 진입 흐름.
9. (선택) `app/memory/*` — `DecisionRetrieval` SQL 회상 로직.

### 작업 시 주의

- **`risk_gate` 우회 엣지 추가**, **`risk_gate` 에 LLM 도입**, **ai_agent에 주문 실행 노드 재도입**, **AES 키 정책 변경** 은
  PR 전 반드시 팀 합의가 필요하다. 안전 가정을 깨는 변경이다.
- **`risk_gate` 의 책임 경계를 깨지 말 것.** AI 측 형식·정책 검증만 담당하고,
  포지션 비중·현금 잔고·종목 상태·시장 상태 같은 **실시간 검증은 백엔드 영역**이다.
  `risk_gate` 안에서 broker API 를 호출하는 코드를 도입하면 안 된다.
- **사용자 승인 흐름은 백엔드 책임**이다. AI agent 코드에 `approval_required` 같은
  `flow_status` 값이나 사용자 승인 분기 노드를 다시 도입하지 말 것.
  AI 는 `FinalDecision.risk_level` (low/medium/high) 만 백엔드에 노출한다.
- 프롬프트 변경은 토큰 비용/응답 형식 영향이 크다. `format_instructions` 가 깨지면
  `strategy_manager`/`decision_manager` 가 즉시 hold 로 강등된다.
- `InvestmentAgentState` 에 필드를 추가할 때는 모든 영향 노드의 read 부분도 함께 점검할 것.
