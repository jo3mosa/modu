# AI Backtest

DA 팀이 만든 데이터·시간·트리거 인프라(`ai_agent/backtest/`) 위에 AI 팀이 LangGraph 의사결정 + post_mortem 후처리 + 통계 검정(McNemar / bootstrap CI) + Streamlit 대시보드를 얹은 통합 백테스트.

## 진입점 두 가지

| 명령 | 의사결정 | 용도 | LLM 비용 |
| --- | --- | --- | --- |
| `python -m ai_agent.backtest.run_backtest` | DA `simple_rule_decision` (룰 패턴 stub) | 인프라 검증 | 0 |
| `python -m ai_agent.backtest.run_ai_backtest --mode {A\|B\|random\|mock}` | LangGraph 또는 baseline | 본 실험 | mode에 따라 |

`run_ai_backtest.py`가 본 진입점. `--mode`로 어댑터 전환:

- `random` — LLM 미호출 baseline. 그래프 대비 lift 입증용
- `mock` — DA `simple_rule_decision`. random과 그래프 사이 sanity
- `A` — LangGraph (Bull/Bear → Strategy → Decision). MVP
- `B` — LangGraph (Strategy → Decision 직결). 토론 ablation

## 사전 준비 — DA 절차 그대로

```bash
# ① 코드 pull
git pull origin develop

# ② docker 띄우기 (처음이면 --build 포함)
docker-compose up -d --build

# ③ backend Flyway 마이그레이션 대기
docker logs -f modu-backend | grep "Started ModuBackendApplication"
# 위 라인 보이면 Ctrl+C

# ④ 테이블 4개 생성 확인
docker exec -it modu-postgres psql -U postgres -d modu_db -c "\dt"
# daily_ohlcv / daily_fundamentals / daily_indicators / financial_statements

# ⑤ 데이터 dump import (Slack / Drive에서 받은 modu_analysis_data.sql)
docker exec -i modu-postgres psql -U postgres -d modu_db < modu_analysis_data.sql

# ⑥ 적재량 확인
docker exec -it modu-postgres psql -U postgres -d modu_db -P pager=off -c \
  "SELECT 'ohlcv', COUNT(*) FROM daily_ohlcv UNION ALL
   SELECT 'fund',  COUNT(*) FROM daily_fundamentals UNION ALL
   SELECT 'ind',   COUNT(*) FROM daily_indicators UNION ALL
   SELECT 'fs',    COUNT(*) FROM financial_statements;"
```

## .env 설정

`ai_agent/.env`에 다음 값들이 채워져 있어야 함:

```env
# DB 분리 변수 (run_ai_backtest가 DATABASE_URL로 자동 합성)
DB_HOST=localhost              # ★ 'postgres' 아님. docker 외부 포트 매핑 사용
DB_PORT=5432
DB_NAME=modu_db
DB_USERNAME=postgres
DB_PASSWORD=...

# Mongo
MONGO_URI=mongodb://...

# LLM (debate_* / daily_scan 사용 시 필수)
GMS_KEY=...
ANTHROPIC_API_KEY=...           # claude profile 사용 시
XAI_API_KEY=...                 # grok profile 사용 시
MODEL_PROFILE=gms_4o_mini       # 기본 profile (app/config/llm.py)
```

`DATABASE_URL`이 비어 있으면 `run_ai_backtest`가 위 분리 변수들로 합성한다.
이미 `DATABASE_URL`이 채워져 있으면 그 값 그대로 사용.

## 실행 예시

### 1. 인프라 점검 (LLM 미호출, ~30초)
```bash
python -m ai_agent.backtest.run_ai_backtest \
    --mode random \
    --start 2024-01-02 --end 2024-01-05 \
    --watchlist 005930,000660,035720 \
    --output backtest_out/random_smoke
```
→ trigger 생성 + JSONL 출력 확인. 데이터/Mongo/Postgres 정상이면 통과.

### 2. 본 실험 (debate_1 — Bull/Bear 1라운드 토론, 실 LLM)
```bash
python -m ai_agent.backtest.run_ai_backtest \
    --mode debate_1 \
    --start 2024-01-02 --end 2024-01-31 \
    --watchlist 005930,000660,035720 \
    --output backtest_out/debate_1 \
    --score-after --pm-mock --holding-days 7
```
- `--score-after`: 결정 JSONL을 읽어 raw_return + post_mortem 부착 → `scored_*.jsonl` 생성
- `--pm-mock`: post_mortem도 LLM 미호출 (fake_post_mortem 사용). 회피 시 추가 LLM 비용
- `--benchmark-csv`: KOSPI 일별 종가 CSV 경로 (기본: `data/kospi_daily.csv`). 있으면 `alpha_return` 산출 (= raw_return − 같은기간 KOSPI 수익률)

### 산출되는 추가 메트릭

- **equity_curve.jsonl**: `SimplePortfolio.mark_to_market`가 매일 쌓은 자산 곡선. dashboard 자산 추이 차트 입력.
- **summary `stats.equity_metrics`**: total_return / CAGR / Sharpe / Sortino / Calmar / MaxDD. `event_loop.run()` 종료 시 자동 산출.
- **stop_fills / target_fills**: `SimplePortfolio.evaluate_open_positions`로 보유 기간 중 손절/익절 도달해 자동 청산된 건수. summary `stats`에 포함.

### 3. 토론 round ablation 비교 (debate_0 vs debate_1 vs debate_2)
```bash
for mode in debate_0 debate_1 debate_2; do
    python -m ai_agent.backtest.run_ai_backtest \
        --mode $mode --start 2024-01-02 --end 2024-01-31 \
        --watchlist 005930,000660,035720 \
        --output backtest_out/$mode \
        --backtest-user-id 9900${mode#debate_} \
        --score-after --pm-mock
done
```
`--backtest-user-id`를 mode별로 분리해 memory_context 오염 차단.

### 4. 결과 시각화
```bash
cd C:/Users/SSAFY/Desktop/modu-reference/ai_agent
pip install -r requirements-dashboard.txt
streamlit run dashboards/backtest_viewer.py
# → http://localhost:8501
# 사이드바에서 backtest_out/debate_1/scored_*.jsonl 등 선택
```

## dummy data로 빠른 검증 (Postgres/Mongo 없이)

```bash
# 더미 JSONL 생성 (DA 형식 그대로)
python -m ai_agent.backtest.examples.generate_dummy_jsonl

# scoring + 가짜 post_mortem 부착
python -c "
from pathlib import Path
from ai_agent.backtest.scoring import score_with_post_mortem
from ai_agent.backtest.examples.generate_dummy_jsonl import DummyPriceFetcher, fake_post_mortem
for f in sorted(Path('ai_agent/backtest/dummy').glob('triggers_*.jsonl')):
    out = f.parent / f.name.replace('triggers_', 'scored_')
    score_with_post_mortem(f, out, price_fetcher=DummyPriceFetcher(seed=42), post_mortem_fn=fake_post_mortem)
"

# 대시보드
streamlit run ai_agent/dashboards/backtest_viewer.py
```

## 디렉터리 구조

모든 backtest 자산이 `ai_agent/backtest/` 한 곳에 모입니다.

```
ai_agent/backtest/
├── (DA framework + AI 어댑터 코드)
├── adapters/                 # graph_decision, random_decision
├── examples/                 # mock_decision, generate_dummy_jsonl
├── scripts/                  # fetch_kospi 등 1회성 스크립트
├── data/                     # 정적 입력 (kospi_daily.csv 등) — gitignore
├── dummy/                    # dummy JSONL 산출물 — gitignore
├── runs/                     # ★ backtest 실행 결과 — gitignore
│   ├── debate_1_2week/
│   │   ├── triggers_2024-01-02.jsonl   # DA event_loop 결과
│   │   ├── scored_2024-01-02.jsonl     # --score-after 결과
│   │   └── summary_<run_id>.json       # 전체 통계
│   ├── debate_1_2024/
│   └── ...
└── logs/                     # nohup 로그 — gitignore
```

`run_ai_backtest --output` default가 `runs/default`. 모드/기간별로 `runs/debate_1_2024` 같이 지정 권장.

`scored_*.jsonl`이 dashboard / LLM-as-Judge의 평가 입력.

## 자주 발생하는 에러

| 증상 | 원인 / 해결 |
| --- | --- |
| `could not translate host name "postgres"` | `.env`의 `DB_HOST=localhost`로 변경 (docker 외부 포트 사용) |
| `필수 환경변수 누락: DATABASE_URL` | 분리 변수(DB_HOST/PORT/NAME/USERNAME) 모두 채워졌나 확인 |
| `MONGO_URI 누락` | docker compose의 `mongo` 외부 포트 매핑 확인 + `.env`에 `MONGO_URI=mongodb://localhost:포트` |
| `GMS_KEY가 .env에 없습니다` | debate_* / daily_scan 사용 시 필수 |
| watchlist 없는 날 — fill 0건 | `--start/--end` 기간 내 daily_ohlcv가 있는지 dump import 확인 |
