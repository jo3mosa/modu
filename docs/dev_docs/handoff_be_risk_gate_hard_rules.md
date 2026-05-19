# 핸드오프 — BE: risk_gate hard rule (이중 게이트) 백엔드 분담

> 다른 Claude 세션 / BE 담당자에게 그대로 전달하기 위한 문서.
> AI 측은 이 문서 기준으로 1차 hard rule을 이미 구현·머지 가능 상태로 만들어 둠.

---

## 1. 배경 — 무엇을 풀고 있는가

risk_gate가 다음 3가지를 hard rule로 검증해야 한다는 요구가 있다:

1. **AI agent 사용 금액 제한** — 사용자가 AI에게 위임한 운용 자금 한도
2. **익절 기준 (목표 수익률)** — `take_profit_pct`
3. **손절 기준 (최대 허용 손실률)** — `stop_loss_pct`

현재 AI `risk_gate`(`ai_agent/app/agents/decision/risk_gate.py`)는 형식 검증 + `allow_auto_trade`만 보고 있어 위 3개를 강제하지 못한다. 백엔드 `SignalHandlerService`도 일부만 가드(자동매매 ACTIVE, daily_loss_limit_amount, 잔고)하고 사용자 한도와의 정합성은 검증하지 않는다.

## 2. 합의된 책임 분배 — 옵션 C (이중 게이트)

| 검증 위치 | 역할 | 데이터 소스 |
|---|---|---|
| **AI `risk_gate` (1차)** | 결정 자체의 사용자 한도 정합성 hard rule | `state.user_context.risk_rules`, `state.portfolio_snapshot` (graph 진입 시점 스냅샷) |
| **BE `SignalHandlerService` (2차)** | KIS 호출 직전 실시간 데이터로 누적·잔고 hard rule | DB 실시간 SELECT |

AI는 publish~체결 사이의 stale 가능성을 인지하고 **명백한 결정 단계 위반**만 거른다. BE는 ACID 트랜잭션 안에서 **누적·실시간 한도**를 최종 보장한다.

> CLAUDE.md `§Part 2 — AI agent workflow (LangGraph) — Important invariants` 2번 항목 ("risk_gate는 AI 측 형식 검증만 담당") 갱신이 필요하다. AI 측 PR에서 함께 반영 예정이며 BE 머지 후 최종 문구 조정.

## 3. AI 측 완료 사항 (BE가 손댈 필요 없음)

| 파일 | 변경 |
|---|---|
| `ai_agent/app/agents/decision/risk_gate.py` | hard rule 3종 추가 — BUY 손절 / SELL 익절 / 단일 주문 AI 운용 한도. 데이터 None이면 검증 skip (backward compat) |
| `ai_agent/app/context/user_context.py` | `risk_rules.ai_budget_amount` 키 노출 자리 확보 + `_fetch_trading_rules` SELECT 확장 TODO 주석 |
| `ai_agent/tests/agents/decision/test_risk_gate.py` | 15개 케이스 신규 (전부 통과) |

검증 식:

- **BUY 손절**: `stop_loss_price >= target_price × (1 - stop_loss_pct / 100)` — 위반 시 `flow_status="blocked"`
- **SELL 익절**: `target_price >= avg_price × (1 + take_profit_pct / 100)` — 위반 시 `blocked`
- **단일 주문 AI 운용 한도**: `order_amount <= ai_budget_amount` — 위반 시 `blocked`

각 검증은 데이터가 없으면 (사용자 미설정, BE 컬럼 미도입, 평단가 없음 등) **자동 skip**하므로 BE 작업이 끝나기 전에도 안전하게 머지 가능하다.

## 4. BE 작업 요청 — 단계별

### 4-1. DB 마이그레이션 (NEW)

`backend/src/main/resources/db/migration/V<UTC-timestamp>__add_ai_budget_to_trading_rules.sql`:

```sql
ALTER TABLE trading_rules
    ADD COLUMN ai_budget_amount BIGINT NOT NULL DEFAULT 0;
ALTER TABLE trading_rule_histories
    ADD COLUMN ai_budget_amount BIGINT NOT NULL DEFAULT 0;

-- 기존 row의 DEFAULT 채움이 끝났다는 가정. NOT NULL 유지하되 DEFAULT는 제거하여
-- 신규 row 입력 시 명시적 값 요구.
ALTER TABLE trading_rules
    ALTER COLUMN ai_budget_amount DROP DEFAULT;
ALTER TABLE trading_rule_histories
    ALTER COLUMN ai_budget_amount DROP DEFAULT;
```

- 명명 패턴은 기존 `V20260508094900__alter_trading_rules_for_risk_management.sql`을 따른다.
- `ddl-auto: validate` 라 entity가 컬럼을 알지 못하면 boot가 막힌다 — 마이그레이션과 entity 변경은 같은 PR로 가야 한다.

### 4-2. Entity / DTO / Service

- `backend/.../trading/entity/TradingRule.java`: `aiBudgetAmount` 필드 추가 (`@Column(name="ai_budget_amount", nullable=false)`, `Long`), 생성자/Builder 인자에 포함.
- `backend/.../trading/entity/TradingRuleHistory.java`: 동일.
- `backend/.../strategy/service/StrategyRuleService.java`: 생성/수정 요청 DTO에서 `aiBudgetAmount` 받아 entity에 세팅. validation은 `> 0` 정도 (단일 주문 한도 0은 의미가 없으므로 차단).
- 단위 합의 — `BIGINT` = **KRW 정수**. `stop_loss_pct` / `take_profit_pct`도 동일 BIGINT 컬럼이며 AI는 **% 정수/실수**로 가정하고 검증 중. **단위 컨벤션을 BE/AI 양쪽 문서에 명시할 것** (현재는 묵시적).

### 4-3. SignalHandlerService 누적·정합성 hard rule

`backend/.../ai/service/SignalHandlerService.java`의 `resolveExecutionStatus`에 다음을 추가:

```java
// (BUY 한정) 단일 주문 + 오늘 누적 매수액이 AI 운용 한도를 초과하면 BLOCKED.
// AI risk_gate가 단일 주문은 1차 검증하지만 누적은 실시간 데이터가 필요하므로 BE 영역.
// dailyLossLimitAmount(손실 한도) 와는 의미가 다른 별도 한도이므로 분리.
if ("buy".equalsIgnoreCase(side) && exceedsAiBudget(m.userId(), nullToZero(fd.orderAmount()))) {
    return AiExecutionStatus.BLOCKED;   // APPROVAL_REQUIRED 아님 — hard rule
}
```

```java
private boolean exceedsAiBudget(Long userId, long orderAmount) {
    TradingRule rule = tradingRuleRepository.findById(userId).orElse(null);
    if (rule == null || rule.getAiBudgetAmount() == null || rule.getAiBudgetAmount() <= 0) {
        return false;  // 미설정 → 검증 skip (AI risk_gate와 동일 정책)
    }
    long todayTotal = orderRepository.sumTodayBuyAmount(userId);
    return todayTotal + orderAmount > rule.getAiBudgetAmount();
}
```

순서 권고: 기존 `exceedsDailyBuyLimit`(APPROVAL_REQUIRED 분기)보다 **앞에** 두어 hard rule이 먼저 판정되도록.

> SELL 익절·BUY 손절의 가격 정합성은 백엔드에서 다시 검증할 필요가 없다 — 이 두 항목은 평단가/목표가 비교라 stale 영향이 적고, AI 1차 hard rule이면 충분하다고 합의됨. BE는 **누적 한도만** 추가 검증.

### 4-4. API / 프론트엔드 노출 (별도 작업 가능)

- 마이페이지 / 온보딩 화면에서 사용자가 `ai_budget_amount`를 입력할 수 있어야 한다.
- 단위는 **KRW 정수** (예: 1000000 = 100만 원). UI에서 콤마 표기 후 BE 전송 시 정수 변환.
- 미설정 시 검증 skip이므로, 기본값(0) → "AI 한도 미설정"으로 UI에 표기 권고.

### 4-5. 백테스트 영향

`ai_agent/backtest/run_ai_backtest.py:92-118`의 `_ensure_backtest_user`는 `users` row만 INSERT한다. **`trading_rules` row는 만들지 않는다** — 즉 backtest_user_id=99999로 돌리면 `_fetch_trading_rules`가 빈 dict를 반환하고, AI risk_gate의 3가지 hard rule은 전부 skip. 정상 동작.

다만 BE가 `ai_budget_amount`를 NOT NULL로 추가하면 *기존* trading_rules row를 가진 사용자의 backtest 시 영향이 있을 수 있다. backtest 시드 SQL을 별도로 관리한다면 함께 갱신 필요.

## 5. 단위·컨벤션 합의 사항

| 항목 | 단위 | 비고 |
|---|---|---|
| `stop_loss_pct` | % 정수/실수 (예: 5 = 5%) | DB는 `BIGINT NOT NULL`. AI는 이 가정으로 비교. **명세 문서화 필요.** |
| `take_profit_pct` | 동일 | 동일 |
| `ai_budget_amount` | KRW 정수 | `BIGINT NOT NULL`. 0 = 미설정 (검증 skip) |

## 6. 검증 체크리스트 (BE 머지 시)

- [ ] 마이그레이션 파일이 `V<UTC-timestamp>__add_ai_budget_to_trading_rules.sql` 패턴
- [ ] `./gradlew bootRun` 성공 (ddl-auto: validate 통과 = 마이그레이션·엔티티 정합)
- [ ] `./gradlew test --tests "*StrategyRuleServiceTest"` 통과 (입력 검증)
- [ ] `./gradlew test --tests "*SignalHandlerServiceTest"` 에 ai_budget 시나리오 추가:
  - 미설정 → 통과
  - 단일 주문이 한도 이하 → READY
  - 누적 + 단일 합이 한도 초과 → BLOCKED (APPROVAL_REQUIRED 아님)
- [ ] BE 머지 후 AI 측 후속 작업:
  - `ai_agent/app/context/user_context.py`의 `_fetch_trading_rules` SELECT에 `ai_budget_amount` 추가
  - 통합 테스트 (가능하면 actual DB) 한 번
  - CLAUDE.md `§Part 2 — Important invariants` 2번 문구를 옵션 C 책임 분배로 정정

## 7. 머지 전 주의사항

- `infra/k8s/backend.yaml` / `frontend.yaml`의 `BACKEND_IMAGE` / `FRONTEND_IMAGE` 플레이스홀더는 절대 손대지 말 것 (Jenkins sed-substitute 대상).
- 이미 적용된 Flyway `V*.sql`은 편집 금지 — 새 파일만 추가.
- KIS 키 암호화 관련 코드(`KIS_ENCRYPTION_KEY` 사용처)는 본 작업과 무관 — 손대지 말 것.
- 머니패스 critical zone이므로 `--no-verify` 등 hook 우회 금지.

## 8. 참고 — 현재 AI risk_gate 구현 요지

```text
state.user_context.risk_rules = {
    "stop_loss_pct":    int|float|None,  # DB.trading_rules.stop_loss_pct
    "take_profit_pct":  int|float|None,  # DB.trading_rules.take_profit_pct
    "ai_budget_amount": int|None,        # DB.trading_rules.ai_budget_amount (BE 컬럼 추가 후 활성화)
}

state.portfolio_snapshot = {
    "positions": [ { "stock_code": str, "average_price": float, "quantity": int }, ... ],
    # mock_trigger는 "holdings" 키 — risk_gate가 양쪽 지원
}
```

각 검증의 위반 시 결과:
```text
{
  "risk_cleared": False,
  "risk_check_result": {"status": "blocked", "reason": "...", "checks": [...]},
  "flow_status": "blocked",
}
```

→ Kafka `ai.decision.generated` 메시지의 `flow_status="blocked"` 가 그대로 BE에 도달하고, `SignalHandlerService.resolveExecutionStatus`가 이미 `BLOCKED`로 매핑함 (line 140-142). 별도 매핑 추가 불필요.

---

문서 작성: AI 파트
대상: BE 담당 / Claude 세션
관련 이슈: S14P31B106-XXX (생성 필요)
