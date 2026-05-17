"""백테스트 동작 검증용 mock 의사결정·사용자·체결 묶음.

AI 팀이 본인 LangGraph + LLM 의사결정으로 갈아끼우기 전까지 인프라가 끝까지
도는지 smoke test 용. 어떤 로직이든 같은 시그니처(interfaces.Protocol) 만
지키면 즉시 교체 가능.

내용:
  - simple_rule_decision: rule_id 패턴 → BUY/SELL/HOLD 단순 매핑
  - flat_user_context  : 정책·잔고 고정 dict
  - SimplePortfolio    : 현금·보유 dict + next-day 시가 체결 (수수료/세금 단순 ratio)
"""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from datetime import date
from typing import Any, Optional

from ..interfaces import Decision, Fill, Trigger


# ─── decision_fn ────────────────────────────────────────────────────────────

_BUY_RULES  = {"RSI-002", "RSI-004", "MFI-002", "BB-002", "SENT-001", "MACD-001"}
_SELL_RULES = {"RSI-001", "RSI-003", "MFI-001", "BB-001",
               "PRICE-002", "SENT-002", "DART-001"}


def simple_rule_decision(trigger: Trigger, user_context: dict,
                          portfolio_snapshot: Any) -> Decision:
    """rule_ids 다수결로 BUY/SELL/HOLD. 진짜 의사결정 알고리즘은 아니고 인프라
    검증용 placeholder — 항상 hold 만 반환해도 백테스트 흐름은 동일.
    """
    buys = sum(1 for r in trigger.rule_ids if r in _BUY_RULES)
    sells = sum(1 for r in trigger.rule_ids if r in _SELL_RULES)

    if buys > sells:
        action, amount = "buy", _budget_amount(user_context, trigger.close_price)
    elif sells > buys:
        action, amount = "sell", _holding_amount(portfolio_snapshot, trigger.stock_code)
    else:
        return Decision(action="hold", reasoning="rules tied")

    return Decision(
        action=action,
        order_amount=amount,
        confidence=min(1.0, abs(buys - sells) / max(1, len(trigger.rule_ids))),
        reasoning=f"rules={trigger.rule_ids}",
    )


def _budget_amount(user_context: dict, price: Optional[float]) -> Optional[int]:
    if not price:
        return None
    budget = float(user_context.get("buy_budget_krw") or 0)
    if budget <= 0:
        return None
    return max(1, int(budget // price))


def _holding_amount(snapshot: Any, stock_code: str) -> Optional[int]:
    """SimplePortfolio.snapshot 의 holdings dict 에서 종목 보유 수 조회.
    스냅샷 구조가 다르면 None — Decision.order_amount=None 으로 흘러감.
    """
    try:
        return int(snapshot.get("holdings", {}).get(stock_code, 0)) or None
    except Exception:
        return None


# ─── user_context_fn ────────────────────────────────────────────────────────

def flat_user_context(day: date, user_id: str) -> dict:
    """정책·잔고가 시간에 따라 변하지 않는 단순 사용자. AI 팀이 정책별 다중
    사용자를 비교하려면 user_id 분기 + Postgres user_context 조회로 교체.
    """
    return {
        "user_id": user_id,
        "risk_profile": "moderate",
        "buy_budget_krw": 1_000_000,   # 매 매수마다 100만원 한도 (단순)
    }


# ─── PortfolioFn ────────────────────────────────────────────────────────────

@dataclass
class SimplePortfolio:
    """v1 placeholder — pending order 패턴으로 시점 정합성 보장.

    체결 모델:
      - Day T 결정은 `execute()`에서 pending_orders에 적재만. cash / holdings /
        open_positions는 미변경. record에는 예약 Fill을 그대로 발행 (체결가는
        T+1 시가로 확정 — 다음 사이클에 그대로 적용되므로 의미 정합).
      - Day T+1 사이클 시작 시 `settle(day)`가 pending을 실제 적용.
      - 따라서 같은 사이클의 evaluate_open_positions / mark_to_market은 아직
        settle 안 된 새 진입을 보지 않음 → "T+1 진입을 T 데이터로 평가"하는
        시점 꼬임 해결.
      - 동일 사이클에 같은 종목 다중 매수/매도가 큐잉되어도 over-commit 안
        되게 execute()는 `_available_cash` / `_sellable_qty` (pending 반영)로 검사.

      가격/수수료:
      - next-day open 가격으로 시장가 체결
      - 수수료 0.015% (양방향), 매도시 거래세 0.20% (KRX 표준)
      - 보유 기간 동안 매일 intraday high/low로 stop_loss/target_price 평가 →
        도달 시 자동 청산 (evaluate_open_positions)
      - 슬리피지·갭상하한 미고려. 부분체결은 cash/holdings 부족 시만 발생.

    open_positions:
      {stock_code: {entry_price, stop_loss_price, target_price, entry_date}}
      단일 포지션 모델 (한 종목 = 한 포지션). 추가 매수 시 entry_price는
      거래량 가중 평균, stop/target은 최신 Decision 값으로 덮어쓴다.

    pending_orders:
      settle 호출 전까지 미적용 상태. 백테스트 마지막 일자에 남은 pending은
      체결되지 않고 경고만 — record에는 이미 Fill이 발행되어 있으므로
      "그날 결정 + 다음날 체결 가정" 시뮬레이션은 그대로 유효.

    initial_holdings:
      backtest 시작 시 보유 종목 seed. SELL 결정이 정상 체결되려면 필수.
    """
    user_id: str = "backtest-user"
    initial_cash_krw: float = 10_000_000
    initial_holdings: dict[str, int] | None = None
    fee_ratio: float = 0.00015
    sell_tax_ratio: float = 0.0020   # KRX 표준 매도세 (증권거래세+농특세)

    cash: float = field(init=False)
    holdings: dict[str, int] = field(default_factory=dict)
    open_positions: dict[str, dict] = field(default_factory=dict)
    pending_orders: list[dict] = field(default_factory=list)
    equity_curve: list[dict] = field(default_factory=list)

    def __post_init__(self):
        self.cash = self.initial_cash_krw
        if self.initial_holdings:
            self.holdings = {str(k): int(v) for k, v in self.initial_holdings.items()}

    def snapshot(self) -> dict:
        return {
            "user_id": self.user_id,
            "cash": self.cash,
            "holdings": dict(self.holdings),
            "open_positions": {
                code: {**pos, "entry_date": pos.get("entry_date").isoformat()
                       if isinstance(pos.get("entry_date"), date) else pos.get("entry_date")}
                for code, pos in self.open_positions.items()
            },
        }

    def execute(self, trigger: Trigger, decision: Decision,
                market_state: dict) -> Optional[Fill]:
        """Day T 결정 → Day T+1 시가 체결 "예약". 실제 적용은 settle(T+1).

        cash / holdings / open_positions는 미변경. pending_orders에 적재만.
        Fill은 record 작성용으로 반환 (예약 가격 = T+1 시가).

        같은 사이클에 같은 종목 다중 주문이 들어와도 over-commit 안 되게
        _available_cash / _sellable_qty가 pending 누계를 반영.
        """
        if decision.action == "hold":
            return None

        next_open = market_state.get("open")
        next_date = market_state.get("date")
        if next_open is None or not decision.order_amount:
            return Fill(fill_date=next_date or trigger.as_of_date,
                        fill_price=None, filled_amount=None,
                        notes="missing_next_open_or_amount")

        amount = int(decision.order_amount)
        fill_date = next_date or trigger.as_of_date

        if decision.action == "buy":
            available = self._available_cash()
            fee = next_open * amount * self.fee_ratio
            total = next_open * amount + fee
            if total > available:
                # 현금 부족(동일 사이클 pending buy 누계 반영) — 가능 수량만 부분체결
                amount = max(0, int(available / (next_open * (1 + self.fee_ratio))))
                if amount == 0:
                    return Fill(fill_date=fill_date, fill_price=None,
                                filled_amount=0, notes="insufficient_cash")
                fee = next_open * amount * self.fee_ratio
                total = next_open * amount + fee
            self.pending_orders.append({
                "side": "buy",
                "stock_code": trigger.stock_code,
                "qty": amount,
                "price": float(next_open),
                "fee": float(fee),
                "tax": 0.0,
                "total_cash": float(total),
                "stop_loss_price": decision.stop_loss_price,
                "target_price": decision.target_price,
                "fill_date": fill_date,
            })
            return Fill(fill_date=fill_date, fill_price=float(next_open),
                        filled_amount=amount, fee=float(fee), tax=0.0,
                        notes="next_day_open_buy")

        if decision.action == "sell":
            sellable = self._sellable_qty(trigger.stock_code)
            amount = min(amount, sellable)
            if amount <= 0:
                return Fill(fill_date=fill_date, fill_price=None,
                            filled_amount=0, notes="no_holdings")
            gross = next_open * amount
            fee = gross * self.fee_ratio
            tax = gross * self.sell_tax_ratio
            net = gross - fee - tax
            self.pending_orders.append({
                "side": "sell",
                "stock_code": trigger.stock_code,
                "qty": amount,
                "price": float(next_open),
                "fee": float(fee),
                "tax": float(tax),
                "total_cash": float(net),
                "fill_date": fill_date,
            })
            return Fill(fill_date=fill_date, fill_price=float(next_open),
                        filled_amount=amount, fee=float(fee), tax=float(tax),
                        notes="next_day_open_sell")

        return None

    def _available_cash(self) -> float:
        """현재 cash에서 pending buy로 commit된 금액을 차감한 가용 cash."""
        committed = sum(p["total_cash"] for p in self.pending_orders if p["side"] == "buy")
        return max(0.0, self.cash - committed)

    def _sellable_qty(self, stock_code: str) -> int:
        """현재 보유에서 pending sell로 commit된 수량을 차감한 매도 가능 수량."""
        held = self.holdings.get(stock_code, 0)
        committed = sum(
            p["qty"] for p in self.pending_orders
            if p["side"] == "sell" and p["stock_code"] == stock_code
        )
        return max(0, held - committed)

    def settle(self, day: date) -> list[Fill]:
        """매 사이클 시작에 호출 — 이전 사이클에 예약된 pending_orders를 실제 적용.

        cash / holdings / open_positions 갱신. 처리한 항목은 pending에서 제거.
        반환 Fill은 event_loop의 stats용 (record에는 이전 사이클 execute가 이미
        Fill을 발행했으므로 별도 기록 안 함 — 중복 방지).
        """
        if not self.pending_orders:
            return []
        applied: list[Fill] = []
        for order in self.pending_orders:
            code = order["stock_code"]
            qty = int(order["qty"])
            if order["side"] == "buy":
                self.cash -= float(order["total_cash"])
                self.holdings[code] = self.holdings.get(code, 0) + qty
                self._record_open_position(
                    stock_code=code, add_qty=qty, add_price=float(order["price"]),
                    stop_loss=order.get("stop_loss_price"),
                    target=order.get("target_price"),
                    entry_date=order["fill_date"],
                )
            else:  # sell
                held = self.holdings.get(code, 0)
                actual_qty = min(qty, held)
                if actual_qty <= 0:
                    # evaluate_open_positions가 같은 사이클에 이미 자동 청산한 경우.
                    # pending sell을 그대로 적용하면 없는 주식에 현금이 생기는 phantom cash 발생.
                    logger.warning(
                        "settle: sell skip — 보유 없음 stock=%s "
                        "(evaluate_open_positions 자동 청산 가능성)", code,
                    )
                    continue
                # 실제 체결 수량 기준으로 현금 재산정 (partial 포함)
                gross = float(order["price"]) * actual_qty
                fee = gross * self.fee_ratio
                tax = gross * self.sell_tax_ratio
                self.cash += gross - fee - tax
                remaining = held - actual_qty
                if remaining <= 0:
                    self.holdings.pop(code, None)
                    self.open_positions.pop(code, None)
                else:
                    self.holdings[code] = remaining
                qty = actual_qty  # Fill에 실제 체결 수량 반영
                order = {**order, "fee": fee, "tax": tax}
            applied.append(Fill(
                fill_date=order["fill_date"], fill_price=float(order["price"]),
                filled_amount=qty, fee=float(order["fee"]), tax=float(order["tax"]),
                notes=f"settled_{order['side']}",
            ))
        self.pending_orders.clear()
        return applied

    def _record_open_position(
        self, *, stock_code: str, add_qty: int, add_price: float,
        stop_loss: Optional[float], target: Optional[float],
        entry_date: date,
    ) -> None:
        """매수 체결 후 open_positions 메타 갱신.

        - 신규 진입: 그대로 저장
        - 추가 매수: entry_price는 거래량 가중 평균.
          stop_loss / target_price는 None이 아니면 덮어쓴다 (최신 판단 우선).
        """
        existing = self.open_positions.get(stock_code)
        if existing is None:
            self.open_positions[stock_code] = {
                "entry_price": float(add_price),
                "entry_qty": int(add_qty),
                "stop_loss_price": float(stop_loss) if stop_loss else None,
                "target_price": float(target) if target else None,
                "entry_date": entry_date,
            }
            return
        prev_qty = max(1, int(existing.get("entry_qty") or 0))
        prev_price = float(existing.get("entry_price") or 0.0)
        new_qty = prev_qty + int(add_qty)
        weighted = (prev_price * prev_qty + float(add_price) * int(add_qty)) / new_qty
        existing["entry_price"] = weighted
        existing["entry_qty"] = new_qty
        if stop_loss is not None:
            existing["stop_loss_price"] = float(stop_loss)
        if target is not None:
            existing["target_price"] = float(target)

    def evaluate_open_positions(
        self, day: date, ohlcv_rows: dict[str, dict]
    ) -> list[Fill]:
        """보유 포지션의 stop_loss / target_price 도달 여부를 매일 평가.

        intraday high/low를 사용:
          - stop_loss_price 설정 + day_low <= stop_loss → stop_loss에 청산
          - target_price 설정    + day_high >= target  → target에 청산
          - 둘 다 도달 시 stop 우선 (보수적 가정 — 장중 순서 알 수 없음)

        체결 시 전 보유 수량 청산 (단순화). fee/tax는 일반 매도와 동일.

        Returns:
            발생한 Fill 리스트. event_loop가 JSONL 레코드로 기록한다.
        """
        if not self.open_positions:
            return []
        fills: list[Fill] = []
        for stock_code in list(self.open_positions.keys()):
            pos = self.open_positions[stock_code]
            qty = self.holdings.get(stock_code, 0)
            if qty <= 0:
                self.open_positions.pop(stock_code, None)
                continue
            row = ohlcv_rows.get(stock_code) or {}
            high = _to_float(row.get("high"))
            low = _to_float(row.get("low"))
            if high is None or low is None:
                continue
            stop = pos.get("stop_loss_price")
            target = pos.get("target_price")

            exit_price: Optional[float] = None
            note: Optional[str] = None
            if stop is not None and low <= stop:
                exit_price, note = float(stop), "stop_loss_hit"
            elif target is not None and high >= target:
                exit_price, note = float(target), "target_hit"
            if exit_price is None:
                continue

            gross = exit_price * qty
            fee = gross * self.fee_ratio
            tax = gross * self.sell_tax_ratio
            self.cash += gross - fee - tax
            del self.holdings[stock_code]
            self.open_positions.pop(stock_code, None)
            fills.append(Fill(
                fill_date=day, fill_price=exit_price, filled_amount=qty,
                fee=fee, tax=tax, notes=f"{note}:{stock_code}",
            ))
        return fills

    def mark_to_market(self, day: date, close_prices: dict[str, float]) -> dict:
        unreal = sum(qty * close_prices.get(sc, 0)
                     for sc, qty in self.holdings.items())
        equity = self.cash + unreal
        snap = {"date": day.isoformat(), "cash": self.cash,
                "unrealized": unreal, "equity": equity,
                "holdings_count": len(self.holdings)}
        self.equity_curve.append(snap)
        return snap


def _to_float(value: Any) -> Optional[float]:
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


# ─── 편의: 의사결정/사용자/포트폴리오 기본 묶음 ────────────────────────────

def build_mock_components(user_id: str = "backtest-user"
                          ) -> tuple[Any, Any, SimplePortfolio]:
    """run_backtest 가 즉시 호출 가능한 v1 stub 묶음."""
    return simple_rule_decision, flat_user_context, SimplePortfolio(user_id=user_id)
