# LangSmith 측정 인프라

MVP.md §측정 인프라에서 명시한 측정 인프라의 구현 가이드입니다.
모든 그래프 실행은 LangSmith로 토큰/비용/latency가 자동 기록되며, 노드별 도메인 metadata가 함께 태깅됩니다.

## 왜 필요한가

MVP 단계부터 측정 인프라를 깔아두는 이유는 다음 두 실험을 **별도 측정 코드 없이 비교 분석**하기 위함입니다.

- **실험 1**: LLM-Wiki refactor 후 토큰 사용량 비교 (DB backend vs Wiki backend)
- **실험 2**: Bull/Bear Ablation (A. 토론+Manager / B. 단일 strategy / C. 라운드 증가)

운영이 시작된 시점부터 LangSmith로 데이터가 누적되어야 비교 분석이 가능합니다.

## 활성화 방법

1. 의존성 설치
   ```bash
   pip install -r ai_agent/requirements.txt
   ```
   `langsmith`, `tiktoken`이 함께 설치됩니다.

2. `.env`에 다음 변수 추가 (`.env.example` 참고)
   ```
   LANGCHAIN_TRACING_V2=true
   LANGCHAIN_API_KEY=<LangSmith API Key>
   LANGCHAIN_PROJECT=modu-mvp
   ```

3. 그래프 실행
   ```bash
   python -m app.triggers.pipeline
   ```

4. LangSmith 대시보드(`https://smith.langchain.com`)의 `modu-mvp` 프로젝트에서 trace 확인

## 비활성화 동작

`LANGCHAIN_TRACING_V2`가 `false`이거나 미설정이면:
- LangChain SDK가 ChatOpenAI trace 자동 기록을 하지 않습니다.
- `app/observability/langsmith_helpers.py`의 `add_run_metadata`는 모두 noop으로 동작합니다.
- **그래프는 정상 동작합니다.** 측정 인프라가 운영을 막지 않는 fail-safe 설계입니다.

## 수집되는 도메인 metadata

`app/observability/langsmith_helpers.py`의 `add_run_metadata()` 호출로 노드별 metadata가 부여됩니다.

| 노드 | metadata 키 |
|------|-------------|
| `context_loader` | `memory_backend` (예: `_NullMemoryStore`, 추후 `DBMemoryStore` / `WikiMemoryStore`) |
| `bull_researcher` | `round` (현재 발언이 누적될 토론 라운드 번호) |
| `bear_researcher` | `round` |
| `strategy_manager` | `winning_side`, `recommended_side`, `confidence` |
| `decision_manager` | `action`, `risk_level`, `history_context_tokens` |
| `risk_gate` | `status`, `risk_cleared`, `approval_required` |

`history_context_tokens`는 `decision_manager`가 입력으로 받은 `history_context`의 tiktoken 토큰 수입니다 (실험 1 비교의 핵심 지표).

## 실험과의 연결

### 실험 1 — DB vs Wiki backend 토큰 비교
같은 anomaly 입력을 두 backend로 각각 실행한 뒤, LangSmith에서 다음 metadata로 필터:
```
memory_backend = "DBMemoryStore"   →  history_context_tokens 평균
memory_backend = "WikiMemoryStore" →  history_context_tokens 평균
```
LLM-as-Judge 품질 점수와 결정 latency도 LangSmith trace에서 함께 비교 가능합니다.

### 실험 2 — Bull/Bear Ablation
3가지 모드(A/B/C)에 대해 backtest 실행 시, 각 trace의 `winning_side` / `confidence` / `action` / 토큰 사용량을 비교합니다. 모드 식별은 향후 `strategy_mode` metadata를 invoke config로 주입해 분리 예정입니다.

## 추가 metadata 부여 방법

새 노드를 만들거나 기존 노드에 추가 metadata가 필요한 경우:

```python
from app.observability.langsmith_helpers import add_run_metadata

def my_node(state):
    ...
    add_run_metadata({"my_key": "my_value"})
    return {...}
```

`add_run_metadata`는 LangSmith 비활성 시 자동 noop이므로 호출 측에서 분기 처리가 필요 없습니다.

## 토큰 카운트 헬퍼

`app/observability/langsmith_helpers.py`의 `count_tokens(text)`는 tiktoken 기반 토큰 수를 반환합니다.

```python
from app.observability.langsmith_helpers import count_tokens
from app.utils.json_utils import to_json

tokens = count_tokens(to_json(state.history_context))
```

`OPENAI_MODEL` 환경변수에 맞는 인코더가 자동 로드되며, 모델 매핑이 없으면 `cl100k_base`로 fallback합니다. tiktoken이 미설치되어 있으면 `0`을 반환합니다(fail-safe).

## 관련 파일

- `ai_agent/app/observability/langsmith_helpers.py` — 헬퍼 모듈
- `ai_agent/.env.example` — LangSmith 환경변수 템플릿
- `ai_agent/requirements.txt` — `langsmith`, `tiktoken` 의존성
- `ai_agent/app/agents/strategy/{bull,bear,strategy_manager}.py` — 토론 노드 metadata 태깅
- `ai_agent/app/agents/decision/{decision_manager,risk_gate}.py` — Decision Team metadata 태깅
- `ai_agent/app/context/context_loader.py` — `memory_backend` metadata 태깅
- `docs/dev_docs/MVP.md` §측정 인프라 — 본 인프라의 의도
