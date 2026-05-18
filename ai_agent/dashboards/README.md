# Backtest Dashboard

`backtest_viewer.py`는 backtest 결과 JSONL을 시각화하는 Streamlit 대시보드다. 6개 탭으로 평가 framework 5축을 한 화면에 모은다.

## 설치

```bash
pip install -r requirements-dashboard.txt
```

production 의존성과 분리되어 있어 `requirements.txt`는 가벼움 유지.

## 실행

```bash
# ai_agent/ 디렉터리에서
streamlit run dashboards/backtest_viewer.py
# → http://localhost:8501 자동 오픈
```

## 데이터 흐름

```
1. backtest 실행 → decisions JSONL
   python -m app.backtest.cli --output runs/decisions_debate_1.jsonl --mode debate_1

2. scorer + post_mortem 후처리 → scored JSONL
   python -c "
   from pathlib import Path
   from app.backtest.scorer import score_with_post_mortem
   from app.backtest.replay_runner import StubPriceFetcher
   score_with_post_mortem(
       Path('runs/decisions_modeA.jsonl'),
       Path('runs/scored_modeA.jsonl'),
       price_fetcher=StubPriceFetcher(),
   )
   "

3. Dashboard에서 scored_modeA.jsonl 선택
```

## 탭 구성

| 탭 | 입력 요구 | 보여주는 것 |
| --- | --- | --- |
| Overview | decisions | 결정 수, BUY/SELL/HOLD 분포, 시간별 결정 수 |
| Quality | **scored** | 모드별 hit rate, 분기별 hit rate 곡선 |
| PnL | **scored** | 거래별 수익률 분포, 누적 수익률 곡선, win rate |
| Calibration | **scored** | Reliability Diagram, ECE, bin별 분포 |
| Reasoning | decisions | 결정 1건의 bull/bear/judgment_reason/post_mortem 전체 텍스트 |
| Comparison | decisions | 두 모드 paired 비교 (결정 일치율, McNemar p-value) |

`scored` 표시된 탭은 scored JSONL 없이는 비활성. decisions JSONL만 있어도 Overview / Reasoning / Comparison(결정 일치율)은 동작.

## 발표 시 권장 화면

1. **Overview** — 전체 그림 한 컷
2. **Quality (분기별)** — memory 누적 효과 곡선
3. **PnL 누적 수익률** — "AI 추천 따라가면 +N%"
4. **Calibration Reliability Diagram** — confidence 정직성 차별점
5. **Reasoning** — 구체적 사례 1~2건 시연

## 캐시 / 성능

- `load_jsonl`은 `@st.cache_data` 적용 — 같은 파일 재로딩 시 즉시
- 큰 JSONL(수천 줄)도 가벼움
- 사이드바 필터(mode/종목/기간)는 모든 탭에 적용
