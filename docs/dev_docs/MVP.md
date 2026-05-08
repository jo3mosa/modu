# MVP

1차 MVP는 LangGraph 기반 멀티 에이전트 의사결정 시스템으로 구성하였습니다. DA팀이 Kafka 토픽으로 push하는 anomaly signals를 트리거로 받아, 이상 신호가 감지된 시점에만 LLM 추론을 수행하는 구조입니다.

## 현재 구조의 핵심 결정

**1. Trigger 기반 호출 (vs 정기 분석)**   
TradingAgents 논문의 정기 분석 방식은 결정 1회당 LLM 11회 호출이 필요해 비용 부담이 큽니다. B2C 개인 투자자 도메인에서는 의미 있는 이상 신호 발생 시점에만 깊은 추론을 수행하는 것이 비용 대비 가치 면에서 우월하다고 판단했습니다.

**2. DB-first Storage**    
현재 구조는 PostgreSQL에서 retrieval하여 과거 결정과 reflection을 조회하는 방식입니다. MVP 단계에서 DB를 선택한 이유는 (1) SQL 집계로 실험 메트릭 분석이 용이하고, (2) TradingAgents 저자들이 v0.2.4에서 BM25 기반 메모리를 폐기하고 단순 log 구조로 회귀한 사례에서 *복잡한 retrieval은 충분한 reflection 누적 후 도입* 이 안전하다는 교훈을 확인했기 때문입니다.

**3. Storage Interface 추상화**   
`MemoryStore` Protocol을 미리 분리하여, MVP에서는 `DBDecisionStore`만 구현하지만 추후 Wiki/Hybrid backend로 교체 시 agent 코드 변경 없이 비교 실험이 가능하도록 설계했습니다.

## 향후 실험 계획

MVP 완성 후 다음 두 가지 실험을 통해 설계 결정을 데이터로 검증합니다.

### 실험 1. LLM-Wiki refactor 후 토큰 사용량 비교

같은 anomaly 입력에 대해 DB backend와 Wiki backend로 각각 결정을 생성하고 다음을 비교합니다.

- `history_context`의 토큰 수 (tiktoken 기준)
- 결정 latency
- LLM-as-Judge 기반 결정 품질 점수
- 결정 일관성 (같은 입력 N회 실행 시 분산)

가설은 *Wiki의 자연어 압축이 토큰을 절감한다* 이지만, 압축 과정에서 정형 데이터의 정밀도가 손실될 가능성도 동시에 검증합니다. Karpathy의 LLM Wiki 사상이 트레이딩 도메인에서도 유효한지 검증합니다.

### 실험 2. Bull / Bear Agent의 효과 검증 (Ablation)

TradingAgents 논문은 Bull/Bear 토론 패턴을 핵심 차별점으로 제시했지만 해당 패턴 자체에 대한 ablation study가 없습니다. 본 실험에서는 다음 세 가지 구조를 backtest로 비교합니다.

- A. Bull/Bear 토론 → Strategy Manager 판결 (현재 MVP)
- B. 단일 Strategy Agent (토론 없이 한 번에 결정)
- C. Bull/Bear 토론 (라운드 수 증가)

같은 anomaly 샘플 50~150개에 대해 각 구조로 결정을 생성하고, 수익률 / 결정 품질 / 토큰 비용을 비교합니다. 토론 패턴이 비용 대비 의미 있는 품질 향상을 가져오는지를 객관적으로 검증하는 것이 목적입니다.

## 측정 인프라

위 두 실험 모두 MVP 단계부터 LangSmith로 토큰/비용/latency를 자동 기록하고, 도메인 metadata(`memory_backend`, `history_context_tokens` 등)를 추가 태깅해두기 때문에 별도 측정 인프라 구축 없이 비교 분석이 가능합니다.  

## MVP 아키텍처
```
decision/
├── state/
│   └── agent_state.py         # AgentState TypedDict
│
├── trigger/                   
│   ├── filter.py              # has_urgent_issue 등 필터링
│   └── emergency_router.py    # critical_danger 즉시 처리
│
├── context/
│   └── context_loader.py      # DB에서 모든 context 수집 (LLM 없음)
│
├── agents/
│   ├── strategy/              
│   │   ├── bull_agent.py
│   │   ├── bear_agent.py
│   │   └── strategy_manager.py
│   └── decision/             
│       ├── decision_agent.py
│       └── risk_gate.py       # deterministic
│   
├── feedback/                  # SELL 결정 시 트리거 (MVP에선 cron 없음)
│   └── post_mortem_agent.py 
│
├── prompts/                   
│   ├── codebook.py            # finance signal 의미
│   └── templates.py           # agent(bull/bear/decision) prompt 템플릿
│
├── memory/ 
│   ├── interfaces.py          # 추상화: MemoryStore Protocol
│   ├── db_store.py            # MVP 구현: DB 기반
│   ├── retrieval.py           # 조회 로직 (DB store 사용)
│   └── memory_log.py          # 저장 로직 (DB store 사용)
│
├── graph/
│   ├── builder.py
│   ├── conditional_logic.py
│   └── runner.py
│
├── observability/             
│   └── langsmith_helpers.py   # metadata 태깅 헬퍼만
│
└── consumer.py                # Kafka signals 토픽 구독
```