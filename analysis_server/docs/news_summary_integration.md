# 뉴스 요약 통합 — analysis_server ↔ ai_agent

이 문서는 트리거 발화 시 함께 전송되는 `news_summary` 의 스키마와, 검증
스크립트 `validate_news_window.py` 가 ai_agent 를 어떻게 호출해야 하는지를 정의한다.

## 1. payload 스키마 변경

`MarketTriggerEvent.analysis_snapshot` 하위에 `news_summary` 키가 신규 추가됨.
**없을 수도 있다** (요약 대상 기사 0건 / Mongo·LLM 일시 실패). agent 는 키 부재
시 종전대로 동작해야 한다 — required 아님.

```jsonc
{
  "event_type": "MARKET_EVENT",
  "stock_code": "005930",
  "timestamp":  "2026-05-18T14:30:00+09:00",
  "trigger": {
    "rule_ids":       ["RSI-001", "BB-001"],
    "trigger_reason": ["RSI 과매수", "볼린저밴드 상단 이탈"]
  },
  "analysis_snapshot": {
    "technical":   { /* ... */ },
    "fundamental": { /* ... */ },
    "event":       { /* ... */ },
    "sentiment":   { /* ... */ },

    // ↓ 신규 — 없을 수도 있음
    "news_summary": {
      "summary":       "삼성전자는 지난 7일간 …",   // string | null
      "window":        { "kind": "days", "value": 7 },
      "article_count": 23,
      "top_articles": [
        {
          "title":           "삼성전자 HBM …",
          "url":             "https://…",
          // published_at: KST naive ISO (offset 미포함) — news_collector
          // 가 strftime("%Y-%m-%dT%H:%M:%S") 로 저장한 stored format 그대로.
          // event 의 top-level timestamp 와 형식이 다른 점에 유의.
          "published_at":    "2026-05-18T09:12:00",
          "sentiment_score": 42
        }
      ]
    }
  }
}
```

### 필드별 의미

| 필드 | 타입 | 설명 |
|---|---|---|
| `summary` | string \| null | LLM 요약 본문. `null` 이면 LLM 호출 실패 — `top_articles` 만으로 판단. |
| `window.kind` | `"days"` \| `"hours"` | 윈도우 단위. 트리거 시점 기준 과거 N 일/시간. |
| `window.value` | int | 윈도우 크기. |
| `article_count` | int | 윈도우 내 매칭 기사 총수 (`top_articles` 길이 ≥ 이 값이 아님). |
| `top_articles` | array | 최신순 최대 5건. 요약 검증·근거 표시용. |

### 윈도우 결정 규칙

`engine/detection_engine.py:RULE_NEWS_WINDOWS` 에 rule_id 별 기본값 정의. 동시
발화 시 가장 긴 윈도우 채택 (`pick_news_window`). 예:
- `RSI-001` (7d) + `BB-001` (14d) 동시 발화 → window = 14d
- `DART-001` (24h) + `RSI-001` (7d) 동시 발화 → window = 7d

## 2. ai_agent 측 처리 권장

- `summary` 가 존재하면 의사결정 노드(Bull/Bear Researcher, Strategy Manager)
  의 컨텍스트에 포함.
- `summary` 가 `null` 이고 `top_articles` 만 있으면 → 제목 + sentiment_score
  만 보고 보조 신호로 활용.
- `news_summary` 키 자체가 없으면 → 기존 sentiment 신호만으로 동작.

## 3. validate_news_window.py — agent 호출 규약

검증 스크립트는 동일 트리거에 대해 n=3/7/14 의 요약을 만들어 agent 결정을
비교한다. 이때 agent 호출 방법을 ai_agent 팀과 합의해야 한다.

### 옵션 A — Kafka 기반 (production 경로 그대로)

장점: 실제 운영 경로 그대로라 통합 회귀 위험 0. 단점: 응답 대기를 위해
별도 response topic 필요, 동기화 까다로움.

```
analysis_server  ──MARKET_SIGNAL_DETECTED──▶  ai_agent
                ◀──MARKET_TRIGGER_RESPONSE──
                   {request_id, decision: BUY|SELL|HOLD}
```

검증 시:
1. payload 에 `request_id` (UUID) 주입
2. 발행 후 `MARKET_TRIGGER_RESPONSE` topic consume 하며 `request_id` 매칭 대기
3. timeout (예: 30초) 시 `UNKNOWN`

### 옵션 B — HTTP 동기 (검증용 별도 엔드포인트)

장점: 구현·디버깅 쉬움. 단점: ai_agent 에 HTTP 서버 추가 부담.

```
POST /trigger/decide
Body: <MarketTriggerEvent payload>
Resp: {"decision": "BUY"|"SELL"|"HOLD", "confidence": 0.0~1.0}
```

### 옵션 C — agent graph 직접 invoke (검증 전용, in-process)

장점: 네트워크 없음, 가장 빠름. 단점: ai_agent 코드를 analysis_server 가
import 해야 함 — 결합도 ↑.

```python
from ai_agent.app.graphs.market_trigger import build_graph
graph = build_graph()
result = graph.invoke({"trigger": payload})
decision = result["decision"]
```

### 권장: 옵션 C → 옵션 A 순서

- **단기 (검증 진행 단계)**: 옵션 C — 한 번에 100~500 샘플 돌릴 거고 production
  변경 없이 가능.
- **중장기 (production 통합 회귀)**: 옵션 A — response topic 정식 추가.

## 4. validate_news_window.py `_call_agent` 구현 가이드

ai_agent 팀과 옵션 확정 후 `_call_agent` 본문을 다음 형태로 채운다.

```python
def _call_agent(stock_code: str, rule_ids: list[str], summary: Optional[str]) -> str:
    # 옵션 C 예시
    from ai_agent.app.graphs.market_trigger import invoke_graph
    payload = _build_test_payload(stock_code, rule_ids, summary)
    return invoke_graph(payload)["decision"]   # "BUY"|"SELL"|"HOLD"
```

`_build_test_payload` 는 `analysis_snapshot.news_summary` 를 빈 dict 가 아닌
실제 윈도우 값으로 채워야 한다 (그것이 검증 대상).

## 5. 변경 이력

- 2026-05-18: 초안 작성. 옵션 A/B/C 후보 제시. 옵션 결정은 ai_agent 팀과 협의 예정.
