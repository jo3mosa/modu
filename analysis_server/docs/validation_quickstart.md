# 뉴스 요약 윈도우 검증 — 내일 아침 실행 가이드

목표: 뉴스 요약 윈도우 n ∈ {3, 7, 14} 중 어느 값이 **결정 안정성** 기준으로
충분한지 확정. ai_agent 의 `run_pipeline` 을 in-process 로 호출해 BUY/SELL/HOLD
결정을 수집하고 일치율 ≥ 90% 인 가장 작은 n 을 채택.

---

## 0. 사전 점검 (1분)

```powershell
cd c:/Users/SSAFY/Desktop/modu/analysis_server
.venv/Scripts/activate
```

확인 사항:
- `analysis_server/.venv` 활성화됨
- `.env` 의 `MONGO_URI`, `GMS_KEY`, `DATABASE_URL` 채워져 있음
- ai_agent 가 의존하는 Postgres(`DATABASE_URL` 가리키는 곳)가 떠있어야 함
  (context_loader 가 user 정보 조회 — DB 죽어있으면 graph 실패)

## 1. 의존성 설치 (한 번만, 약 2분)

ai_agent 의 langgraph + langchain stack 을 analysis_server venv 에 깔아둠.

```powershell
.venv/Scripts/pip install -r ../ai_agent/requirements.txt
```

성공 확인:
```powershell
.venv/Scripts/python -c "from app.graph.runner import run_pipeline; print('OK')"
```
> `app` 모듈을 못 찾는다고 나오면 다음 단계 (스크립트는 자동으로 `ai_agent/`
> 를 sys.path 에 추가하지만, 한 줄짜리 sanity check 라 직접 sys.path 보강 필요):
> ```powershell
> .venv/Scripts/python -c "import sys; sys.path.insert(0, '../ai_agent'); from app.graph.runner import run_pipeline; print('OK')"
> ```

## 2. dummy user_id 결정 (3분)

`run_pipeline` 이 `context_loader` 노드에서 user_id 로 Postgres 조회합니다.

**옵션 A — 기존 user 사용 (안전)**

ai_agent 팀이나 backend 팀에서 "테스트용 user_id" 받기. 또는 직접 확인:

```powershell
.venv/Scripts/python -c "
from sqlalchemy import create_engine, text
import os; from dotenv import load_dotenv; load_dotenv()
e = create_engine(os.environ['DATABASE_URL'])
with e.connect() as c:
    r = c.execute(text('SELECT user_id FROM users LIMIT 5')).fetchall()
    print(r)
"
```
→ 결과 중 아무 user_id 1개 선택 (예: 1).

**옵션 B — user_id=1 로 그냥 시도**

`context_loader` 가 user 없을 때 어떻게 동작하는지에 따라 다름:
- graceful default 반환 → OK
- 예외 raise → 결정 = `UNKNOWN` (검증 영향 받음)

먼저 옵션 B 로 1~2 샘플 돌려보고 UNKNOWN 이 많이 나오면 옵션 A 로 fallback.

## 3. 샘플 CSV 생성 (1분)

MongoDB 에서 "최근 활동량 많은 종목-날짜" 30쌍 자동 picking:

```powershell
.venv/Scripts/python -m scripts.backfill.sample_validation_csv --out samples.csv --n 30
```

출력 예시:
```
✓ 30 샘플 저장: samples.csv
rule_id 분포: Counter({'RSI-001': 3, 'DART-001': 3, 'MACD-001': 3, ...})
```

내용 확인:
```powershell
type samples.csv | Select-Object -First 5
```

> 샘플이 부족하면 (`샘플 0개` 또는 5개 미만) `--lookback 90` 또는 `--lookback 180`
> 으로 룩백 늘리기.

## 4. 검증 실행 (n 샘플 × 3 windows × 1 repeat ≈ 30~60분)

```powershell
.venv/Scripts/python -m scripts.backfill.validate_news_window `
    --csv samples.csv `
    --user-id 1 `
    --out-json validation_result.json
```

> 시간 예산:
> - 샘플당 ~1~2분 (요약 3종 × 60초 + agent 호출 3회 × 30~60초)
> - 30 샘플이면 30~60분
> - 더 빨리 보고 싶으면 `--n 10` 으로 샘플 줄여 CSV 재생성 후 실행

진행 중 콘솔에 매 샘플마다:
```
[3/30] stock=005930 ts=2026-05-15T09:00:00+09:00 rules=MACD-001
```

## 5. 결과 해석

콘솔 마지막에 다음 형태 리포트가 뜸:

```
======================================================================
  뉴스 요약 윈도우 검증 리포트  (샘플 30건)
======================================================================

[결정 분포]
  window       BUY  SELL  HOLD   UNK
  n=3            5     8    15     2
  n=7            6     9    14     1
  n=14           7     8    14     1

[일치율 (인접 윈도우)]
  n=3 vs n=7:   83.3%  (비교 가능 28건)  ✗
  n=7 vs n=14:  92.6%  (비교 가능 27건)  ✓

[권장 n] 7일  (일치율 임계값 90%)
======================================================================
```

해석:
- `n=7 vs n=14` 일치율이 90% 이상 → n=7 로 충분 (n=14 의 추가 컨텍스트가
  결정을 거의 안 바꿈)
- `n=3 vs n=7` 일치율이 90% 미만 → n=3 은 부족 (맥락 손실)
- 결론: **n=7 채택**

## 6. 적용

`engine/detection_engine.py:RULE_NEWS_WINDOWS` 의 일 단위 윈도우들을 권장값으로
일괄 갱신 (또는 그대로 유지). 검증 결과 JSON (`validation_result.json`) 은
보존해서 PR / 보고서에 첨부.

---

## 트러블슈팅

### 3단계 — MongoDB 연결 timeout (`ServerSelectionTimeoutError`)

SSAFY 원격 MongoDB (`k14b106.p.ssafy.io:30017`) 가 응답 없는 경우. 코드 문제가
아니라 SSAFY 인프라 일시 이슈.

**옵션 1 — 잠시 후 재시도**

**옵션 2 — 수동 CSV 작성** (인프라 다운이 길어질 때)

3단계 자동 생성 건너뛰고 `samples.csv` 를 직접 만들기:

```csv
stock_code,timestamp,rule_ids
005930,2026-05-15T09:00:00+09:00,RSI-001
000660,2026-05-15T09:00:00+09:00,MACD-001
035420,2026-05-16T09:00:00+09:00,DART-001
035720,2026-05-16T09:00:00+09:00,SENT-001
373220,2026-05-17T09:00:00+09:00,BB-001
207940,2026-05-17T09:00:00+09:00,RSI-001
005380,2026-05-14T09:00:00+09:00,MACD-001
000270,2026-05-14T09:00:00+09:00,PRICE-001
005490,2026-05-13T09:00:00+09:00,MFI-001
068270,2026-05-13T09:00:00+09:00,DART-001
```

> 종목코드 의미 (참고용):
> 005930=삼성전자, 000660=SK하이닉스, 035420=네이버, 035720=카카오,
> 373220=LG에너지솔루션, 207940=삼성바이오로직스, 005380=현대차,
> 000270=기아, 005490=POSCO홀딩스, 068270=셀트리온

샘플 10개로도 검증은 충분 (특히 `--mode A` 일 때 시간/비용 절감 효과).

### `app.graph.runner import 실패`
- ai_agent deps 설치 안 됨 → 1단계 재실행
- ai_agent venv 가 따로 있는지 확인 (없는 게 정상)

### 모든 결정이 `UNKNOWN`
- user_id 가 DB 에 없음 → 2단계 옵션 A 로 user_id 변경
- Postgres 연결 실패 → `.env` `DATABASE_URL` + 컨테이너 상태 확인
- `run_pipeline` 자체 예외 → 콘솔 traceback 확인

### 일치율이 모두 100%
- agent 가 뉴스 요약을 사실상 안 보고 있을 가능성 → 검증 신뢰도 낮음
- ai_agent 측에서 `analysis_snapshot.news_summary` 를 실제로 prompt 에 포함하는지
  점검 필요 (이번 변경의 ai_agent 측 처리는 추후 작업)

### LLM/API 비용
- 30 샘플 × 3 windows × 1 repeat = 90회 요약 + 90회 agent graph 실행
- agent 그래프는 Bull/Bear/StrategyManager 등 다중 LLM 호출 → 한 회당 5~10 호출
- GMS 크레딧 사전 확인 (특히 mode=A 의 Bull/Bear 토론은 토큰 소비 큼)
- 비용 줄이려면: `--mode B` (단일 에이전트) 또는 `--n 10` (샘플 축소)

---

## 한 줄 요약 (일어나자마자 복붙용)

```powershell
cd c:/Users/SSAFY/Desktop/modu/analysis_server; .venv/Scripts/activate
.venv/Scripts/pip install -r ../ai_agent/requirements.txt
.venv/Scripts/python -m scripts.backfill.sample_validation_csv --out samples.csv --n 30
.venv/Scripts/python -m scripts.backfill.validate_news_window --csv samples.csv --user-id 1 --out-json validation_result.json
```
