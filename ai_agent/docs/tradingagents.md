# TradingAgents — 전문가 레퍼런스 (MODU 적용 관점)

> 이 문서는 Claude가 **MODU AI 에이전트 코드/아키텍처를 리뷰할 때 참조하는 권위 문서**다.
> 논문(arXiv 2412.20138, v7, Yijia Xiao 외, UCLA)과 레포(github.com/TauricResearch/TradingAgents, v0.2.4 기준)를 직접 검증해 작성했다.
>
> **출처 라벨링 규칙**:
> - `[논문]` — 논문 본문에 명시된 사실
> - `[레포]` — GitHub 레포 코드에서 확인한 구현 사실 (논문엔 없음)
> - `[해석]` — 위 두 사실에서 도출한 판단 (반론 가능)

---

## 0. 이 문서의 사용법

MODU 코드/아키텍처를 만질 때 다음 순서로 읽는다:

1. 변경 대상이 §2 역할 계약 중 어떤 것에 해당하는지 먼저 매핑
2. §8 적용 판단 매트릭스에서 해당 패턴의 권장도/위험도 확인
3. §9 리뷰 체크리스트로 현재 변경이 함정에 빠지는지 검증
4. §10 알려진 함정 항목과 비교

---

## 1. 핵심 contribution (사실 vs 과장)

### 1.1 논문이 실제로 주장하는 것 `[논문]`
1. **트레이딩 회사 협업 구조의 LLM 모사**: 분석가→리서처→트레이더→리스크→펀드매니저 (단순 데이터 수집형 멀티 에이전트가 아님)
2. **이중 토론 게이트**: Bull/Bear (시장 방향) + Aggressive/Neutral/Conservative (리스크) 두 단계 토론
3. **하이브리드 통신**: *"Agents communicate primarily through structured documents and diagrams"* — 자연어 대화는 *"exclusively during agent-to-agent conversations and debates"*
4. **베이스라인 대비 우월한 백테스트 성과** (3개월/3종목 한정)

### 1.2 논문이 *주장하지 않는* 것
- ❌ Reflection/회고 메모리 — **논문엔 전용 섹션 없음**. ReAct 언급만. 정교한 회고는 `[레포]` 한정 엔지니어링 선택.
- ❌ 거래 비용·포지션 사이징 가정 — 본문에 명시 없음 → **백테스트 결과는 보수적으로 해석**할 것
- ❌ 포트폴리오 레벨 상태 관리·멀티 종목 동시 의사결정 — future work
- ❌ Trader/Fund Manager 프롬프트 템플릿 — 부록에 출력 예시(AAPL 2024-11-19)는 있으나 프롬프트는 없음
- ❌ 토론 종료 알고리즘 — *"n rounds, as determined by the debate facilitator"* 정도. 카운터 기반(`count >= 2*N`)은 `[레포]` `tradingagents/graph/conditional_logic.py` 구현 사실

### 1.3 비용 프로파일 (가장 중요) `[논문]`
> **"11 LLM calls & 20+ tool calls/prediction"**

논문 본인이 백테스트를 3개월로 한정한 이유. **MODU는 사용자×종목 단위 봇 루프 구조**라 그대로 차용 시 비용이 곧장 한계가 된다.

---

## 2. 에이전트 역할 계약 (Role Contracts)

| Agent | 입력 | 출력 | 통신 형태 | 출처 |
|---|---|---|---|---|
| Fundamentals Analyst | 재무제표·실적·내부자 거래 | "key metrics, insights, recommendations" 리포트 | 구조화 문서 | `[논문]` |
| Sentiment Analyst | SNS 포스트·센티먼트 점수·내부자 센티먼트 | 투자자 행동 영향 리포트 | 구조화 문서 | `[논문]` |
| News Analyst | 뉴스·정부 발표·거시 지표 | 시장 상태/회사 변화 평가 | 구조화 문서 | `[논문]` |
| Technical Analyst | OHLCV (자산별 맞춤 지표: MACD/RSI…) | "price patterns and trading volumes" 예측 | 구조화 문서 | `[논문]` |
| Bull Researcher | 분석가 4종 리포트 + Bear의 직전 발화 | 강세 논거 (자연어) | 자연어 토론 | `[논문]` |
| Bear Researcher | 분석가 4종 리포트 + Bull의 직전 발화 | 약세 논거 (자연어) | 자연어 토론 | `[논문]` |
| Research Manager (debate facilitator) | Bull/Bear 토론 히스토리 | 우세 관점 선정 + 구조화 entry | 구조화 결정 | `[논문]` |
| Trader | Research Manager 결정 + 분석가 리포트 | 트레이딩 플랜 (포지션·사이즈 초안) | 구조화 (포맷 비공개) | `[논문]`은 책임만, 포맷은 `[레포]` |
| Risk-seeking | Trader 결정 | 공격적 재해석 | 자연어 토론 | `[논문]` |
| Risk-neutral | Trader 결정 | 중립적 재해석 | 자연어 토론 | `[논문]` |
| Risk-conservative | Trader 결정 | 보수적 재해석 | 자연어 토론 | `[논문]` |
| Fund Manager | 리스크팀 토론 | *"determines the appropriate risk adjustments, and updates the trader's decision and report states"* | 구조화 결정 | `[논문]` |
| Execution | (논문 범위 밖) | 실주문 | — | MODU 자체 책임 |

### 핵심 invariant
- **분석가는 구조화 출력만, 토론 노드만 자연어** `[논문]`
- **Fund Manager가 최종 의사결정권자** — Trader는 초안 작성자에 불과 `[논문]`
- **모든 분석가가 모든 정보에 접근하는 게 아님** — 각자 도메인 데이터만 받음 (정보 분리가 토론을 의미 있게 만듦) `[해석]`

---

## 3. 통신 프로토콜

### 3.1 원칙 `[논문]`
- "concise, well-organized" 리포트
- "irrelevant information" 회피
- 구조화 문서가 디폴트, 자연어는 *토론 노드 한정*

### 3.2 MODU 적용 판단 `[해석]`
- `InvestmentAgentState`에 분석가별 필드를 분리해 누적 (예: `fundamentals_report: FundamentalsReport`)
- 토론 노드는 자유 자연어 허용 + history만 state에 누적
- 결정 노드(Trader/Fund Manager/risk_guard)는 **Pydantic enum/숫자만 허용** → executor 입력 안정성 확보
- 잘못된 LLM 출력 시 graceful fallback = `hold` (실거래 안전성 우선)

---

## 4. 토론 메커니즘

### 4.1 Bull/Bear 연구 토론
- **참여자**: Bull Researcher, Bear Researcher `[논문]`
- **포맷**: 자연어, n라운드 `[논문]`
- **종료**: `[논문]`은 "facilitator가 결정", `[레포]` `conditional_logic.py`는 `count >= 2 * max_debate_rounds` (보통 1~2 권장)
- **출력**: facilitator(Research Manager)가 토론 히스토리 리뷰 → 우세 관점 선정 → 구조화 entry로 기록 `[논문]`

### 4.2 3자 리스크 토론
- **참여자**: Risk-seeking, Risk-neutral, Risk-conservative `[논문]`
- **순환**: `[레포]`에서 Aggressive→Conservative→Neutral 사이클
- **종료**: `[레포]` `count >= 3 * max_risk_discuss_rounds`
- **목적**: Trader 결정을 3관점에서 재평가 → Fund Manager가 조정/거부/승인

### 4.3 토론 깊이 vs 비용 트레이드오프 `[해석]`
- 라운드 1당 비용 ≈ LLM 호출 2~3회 추가
- **MODU 시작값 권장**: `max_debate_rounds=1`, `max_risk_discuss_rounds=1`
- 백테스트로 라운드 증가의 sharpe 개선이 비용을 정당화하는지 측정 후 점진적 증가

---

## 5. 메모리/Reflection (⚠️ 레포 한정)

> ⚠️ **이 섹션은 모두 `[레포]` 사실이며 논문 contribution이 아니다.**

- 저장: `~/.tradingagents/memory/trading_memory.md`
- 호출 시점: 신규 분석 시작 시 `memory_log.get_past_context(company_name)`으로 과거 회고를 초기 state에 주입
- 회고 생성: `tradingagents/graph/reflection.py` — 거래 후 결과를 LLM에 회고시켜 메모리 적재
- 체크포인트: `langgraph-checkpoint-sqlite`로 그래프 중단/재개

### MODU 적용 시 변경점 `[해석]`
- 저장소: 마크다운 파일 → **PostgreSQL 테이블** (사용자×종목 스코프 격리, Flyway 마이그레이션으로 관리)
- 체크포인트: SQLite는 K3s/EKS Pod 재기동에 부적합 → **PostgreSQL 또는 Redis** 기반 LangGraph checkpointer
- 회고 트리거: 체결 후 N시간/N일 시점 별도 잡(스케줄러)
- 인용 보안: KIS 키나 사용자 식별 정보는 메모리 텍스트에 남기지 말 것

---

## 6. 비용 프로파일

| 항목 | 수치 | 출처 |
|---|---|---|
| 결정 1건당 LLM 호출 | 11회 | `[논문]` |
| 결정 1건당 툴 호출 | 20+회 | `[논문]` |
| 백테스트 기간 한정 이유 | "intensive LLM and tool use" | `[논문]` |

### MODU 환경 의미 `[해석]`
- 사용자 N명 × 종목 M개 × 일 K회 결정 = **N×M×K × 11 LLM 호출**
- 가상 스레드(`spring.threads.virtual.enabled: true`)는 동시성을 풀 수 있지만 **LLM API 비용·rate limit은 풀어주지 않는다**
- 비용 제어 패턴:
  1. **시간 윈도우 분리**: 일 1회 풀그래프 (장 시작 전) + 장중은 변경분/이벤트 트리거 분석가만
  2. **분석가별 캐시**: dataflows 결과를 Redis에 TTL로 저장, 동일 종목·동일 시간대면 재사용
  3. **토론 라운드 1회 고정** 시작
  4. **분석가 모델 차등화**: 뉴스/센티먼트는 Haiku, 리스크 토론·Fund Manager는 Sonnet/Opus
  5. **사용자 등급별 그래프 깊이**: 무료 사용자는 critic 1패스, 유료는 풀 토론

---

## 7. 실험 설정과 평가 `[논문]`

### 7.1 셋업
- **티커**: AAPL, GOOGL, AMZN
- **기간**: 2024-01-01 ~ 2024-03-29 (3개월)
- **베이스라인**: Buy & Hold, MACD, KDJ+RSI, ZMR, SMA
- **명시되지 않음**: 거래 비용, 슬리피지, 포지션 사이징 룰, 증거금

### 7.2 결과 (Table 1)
| 종목 | CR% | AR% | Sharpe | MDD% |
|---|---|---|---|---|
| AAPL | 26.62 | 30.5 | 8.21 | 0.91 |
| GOOGL | 24.36 | 27.58 | 6.39 | 1.69 |
| AMZN | 23.21 | 24.90 | 5.60 | 2.11 |

### 7.3 결과 해석 시 주의 `[해석]`
- 표본 3종목 × 3개월 → **통계적 유의성 매우 약함**
- 거래 비용 가정 부재 → 한국 시장(매도 거래세 0.18%, 수수료, 슬리피지) 적용 시 결과 크게 달라질 수 있음
- 2024 Q1은 AAPL 약세장(-10%), GOOGL/AMZN 강세장 — 시장 국면 편향 가능성

---

## 8. MODU 적용 판단 매트릭스

| 패턴 | 출처 권위 | MODU 적합도 | 위험 | 결정 |
|---|---|---|---|---|
| 분석가 4분할 (Fund/Tech/News/Sentiment) | `[논문]` 핵심 | 높음 (analysis_server 활용) | 비용↑ | **차용** + 캐시·시간 윈도우 |
| Bull/Bear 토론 + Decision Manager | `[논문]` 핵심 | 중 (critic 강화) | 비용↑, 라운드 결정 어려움 | **차용** (라운드 1로 시작) |
| 3자 리스크 토론 + Fund Manager | `[논문]` 핵심 | 중 | LLM 출력 신뢰 위험 | **변형 차용** (앞단 추가, **`risk_guard` 하드 게이트 유지**) |
| Reflection 메모리 | `[레포]` only | 중 (PostgreSQL로 자연스러움) | 사용자 격리·PII 누출 | **변형 차용** (DB 스코프 격리) |
| SQLite checkpoint | `[레포]` | **부적합** | Pod 재기동에 부적절 | **거부** → PostgreSQL/Redis |
| 결정 노드 structured output | `[논문]` 권장 | 매우 높음 | 거의 없음 | **즉시 채택** |
| yfinance dataflows | `[레포]` | **부적합** | 한국 종목 미지원 | **거부** → pykrx/finance-datareader |
| 단일 사용자 메모리 | `[레포]` | **부적합** | 사용자 데이터 누수 | **거부** → 사용자×종목 스코프 |
| 포트폴리오 레벨 의사결정 | 미커버 | MODU가 더 필요 | — | **MODU 자체 설계** |

---

## 9. MODU 코드 리뷰 체크리스트

### 9.1 그래프 구조를 변경할 때 (`ai_agent/app/graph/builder.py`)
- [ ] `risk_guard`를 우회하는 엣지가 추가되지 않았는가? (CLAUDE.md 원칙)
- [ ] `executor`가 `risk_cleared == True` 외 경로로 도달 가능한가? (절대 안 됨)
- [ ] 새 노드의 **입력은 `InvestmentAgentState`의 어떤 필드**인지, **출력은 어떤 필드에 쓰는지** 명시됐는가?
- [ ] 그래프 깊이 증가 시 결정 1건당 LLM 호출 수를 추정했는가? (TradingAgents 11회를 기준점으로)

### 9.2 새 분석가 노드를 추가할 때
- [ ] 도메인이 기존 분석가와 겹치지 않는가? (정보 분리가 토론 가치를 만듦)
- [ ] 출력이 **구조화 Pydantic 모델**인가? (자연어 리포트는 토론 노드 전용)
- [ ] 데이터 소스가 캐시 가능한가? (analysis_server 결과 재사용)
- [ ] 한국어 프롬프트로 튜닝됐는가? (영어 프롬프트 + 한국어 입력은 품질 저하)

### 9.3 토론 메커니즘을 추가/수정할 때
- [ ] 종료 조건이 **카운터 기반**인가? (LLM이 결정하면 무한 루프 위험)
- [ ] `max_*_rounds` 기본값이 1인가? (비용 안전장치)
- [ ] 토론 결과를 종합하는 **단일 결정 노드**(facilitator/manager)가 있는가?
- [ ] 결정 노드 출력은 **구조화 스키마**인가?

### 9.4 리스크 게이트를 손댈 때 ⚠️
- [ ] 룰 기반 하드 게이트(`risk_cleared` boolean)는 그대로 유지했는가?
- [ ] LLM 기반 리스크 토론은 하드 게이트 **앞단에만** 추가했는가? (대체 아님)
- [ ] 일일 손실 한도, 증거금, 종목별 노출 한도가 룰로 코드화돼 있는가?
- [ ] LLM 출력이 손상돼도 **fail-closed**(거래 차단)인가?

### 9.5 메모리/회고를 추가할 때
- [ ] 저장소가 **PostgreSQL**인가? (마크다운 파일 X, SQLite X)
- [ ] 사용자×종목 스코프로 **격리**돼 있는가?
- [ ] 회고 텍스트에 KIS 키, 사용자 식별 정보, 잔고 절대값이 평문으로 남지 않는가?
- [ ] Flyway 마이그레이션 신규 파일로 추가됐는가? (기존 V*.sql 수정 X)
- [ ] 회고 트리거가 별도 스케줄러 잡인가? (그래프 본체 비용에 추가되면 안 됨)

### 9.6 비용 영향 검토
- [ ] 변경으로 결정 1건당 LLM 호출이 몇 % 증가하는가?
- [ ] 동시 실행 봇 수 × 증가량이 운영 LLM 예산에 들어가는가?
- [ ] 비용 증가 대비 백테스트 sharpe/MDD 개선이 정당화되는가?

---

## 10. 알려진 함정 (MODU 컨텍스트)

1. **비용 폭발**: 분석가 4분할 + 토론 2회 + 리스크 토론 = 결정당 LLM 20+ 호출. 캐시·시간 윈도우 없이 도입하면 운영 비용이 한 자릿수 배 증가.
2. **리스크 게이트 우회**: TradingAgents의 Fund Manager는 LLM 출력에 의존. MODU의 `risk_guard` 하드 게이트를 LLM 토론으로 대체하면 회귀 사고의 토대가 됨. **항상 룰 게이트 + LLM 토론 = AND**.
3. **사용자 데이터 누수**: 단일 사용자 가정의 메모리/체크포인트를 그대로 가져오면 회고 컨텍스트가 사용자 간 누출 가능. 모든 메모리 키에 `user_id` 포함 강제.
4. **한국 시장 시간 압박**: 09:00~15:30 KST + 동시호가. 토론 라운드를 늘리면 호가 타이밍 미스. **장중은 1라운드 고정**, 풀 토론은 장 시작 전 배치잡으로.
5. **거래 비용 무시**: 논문 결과(Sharpe 8.21)는 거래 비용 없는 환경. 한국 거래세·슬리피지·증거금을 백테스트 처음부터 모델링하지 않으면 라이브와 큰 괴리.
6. **분석가 도메인 중첩**: News와 Sentiment를 어설프게 분리하면 같은 정보를 두 번 분석해 비용만 증가. 데이터 소스 단위로 명확히 분리.
7. **structured output 미강제 → executor 회귀**: Trader/Fund Manager 출력이 자유 자연어면 KIS 주문 빌더가 깨짐. Pydantic 강제 + fallback=hold.
8. **체크포인트 로컬 SQLite**: K3s/EKS에서 Pod 재기동 시 체크포인트 유실. 운영은 PostgreSQL/Redis 필수.

---

## 11. 빠른 인용 (코드 리뷰 시 바로 쓰는 한 줄)

- "이건 `[논문]`의 핵심 invariant 위반: 분석가가 자연어 출력하면 안 됨"
- "`[논문]` 비용 프로파일이 11 LLM/결정인데, 이 변경이면 X로 늘어남"
- "이건 `[레포]` 한정 패턴이지 논문 contribution 아님 — MODU에 도입할 ROI 따로 따져야"
- "`risk_guard` 하드 게이트 우회 — CLAUDE.md 원칙 위반"
- "토론 종료를 LLM에 맡기면 무한 루프 위험. 카운터 기반으로"
- "결정 노드 structured output 미강제 → executor 입력 변동성 → 실거래 사고 위험"

---

## 부록 A. 출처 링크
- [arXiv 2412.20138 (논문)](https://arxiv.org/abs/2412.20138)
- [GitHub TauricResearch/TradingAgents](https://github.com/TauricResearch/TradingAgents)
- 관련 코드 핵심 파일 (레포 v0.2.4 기준):
  - `tradingagents/graph/setup.py` — 그래프 와이어링
  - `tradingagents/graph/conditional_logic.py` — 토론 종료 카운터
  - `tradingagents/graph/reflection.py` — 회고 생성
  - `tradingagents/agents/managers/` — Research Manager, Fund (Portfolio) Manager
  - `tradingagents/agents/risk_mgmt/` — Aggressive/Neutral/Conservative

## 부록 B. MODU 그래프 매핑 (현 상태)
- `ai_agent/app/graph/builder.py:41-98`
- `ai_agent/app/state/investment_state.py` — `InvestmentAgentState` 정의
- `ai_agent/app/runtime/executor.py` — KIS 주문 실행 (LLM-free)
- `ai_agent/app/config/prompts/` — 에이전트별 프롬프트
