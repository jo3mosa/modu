"""PortfolioSim.

backtest 중 가상 포트폴리오를 추적한다. KIS API 호출 없이 결정과 가격만으로
포지션/현금/평단가를 갱신한다. 실시간 경로의 portfolio_snapshot 구조와 동일한
dict를 반환해 그래프 안에서 동일하게 사용 가능하다.

MVP 단순화:
- 수수료/세금/슬리피지 없음
- 부분체결 없음 (지정 금액으로 가능한 만큼 매수, 부족분은 cash 부족 시 무시)
- target_price/stop_loss_price는 PortfolioSim이 평가하지 않음 (결정 시점 종가로 즉시 체결)
"""
from typing import Any


class PortfolioSim:
    def __init__(self, initial_cash: int = 10_000_000) -> None:
        self._cash: int = initial_cash
        self._positions: dict[str, dict[str, float]] = {}

    def snapshot(self) -> dict[str, Any]:
        """그래프 노드들이 읽는 portfolio_snapshot 형태로 반환."""
        holdings = [
            {
                "stock_code": code,
                "quantity": pos["quantity"],
                "avg_buy_price": pos["avg_buy_price"],
            }
            for code, pos in self._positions.items()
            if pos["quantity"] > 0
        ]
        return {
            "cash": self._cash,
            "holdings": holdings,
            "total_assets": self._cash + self._unrealized_value(),
        }

    def apply_decision(
        self,
        stock_code: str,
        side: str | None,
        order_amount: int | None,
        execution_price: float,
    ) -> dict[str, Any]:
        """결정을 portfolio에 반영하고 체결 결과를 반환한다.

        side: 'buy' / 'sell' / None(=hold)
        order_amount: 매수 시 사용할 KRW. 매도는 평가금액 기준.
        execution_price: 체결 단가 (해당 거래일 종가 사용)
        """
        if side is None or side == "hold" or not order_amount or execution_price <= 0:
            return {"executed": False, "reason": "hold or invalid params"}

        if side == "buy":
            return self._buy(stock_code, order_amount, execution_price)
        if side == "sell":
            return self._sell(stock_code, order_amount, execution_price)
        return {"executed": False, "reason": f"unknown side: {side}"}

    def _buy(self, code: str, krw: int, price: float) -> dict[str, Any]:
        spend = min(krw, self._cash)
        if spend <= 0:
            return {"executed": False, "reason": "insufficient cash"}
        qty = spend / price
        pos = self._positions.setdefault(code, {"quantity": 0.0, "avg_buy_price": 0.0})
        new_qty = pos["quantity"] + qty
        pos["avg_buy_price"] = (
            (pos["avg_buy_price"] * pos["quantity"] + price * qty) / new_qty
            if new_qty > 0 else 0.0
        )
        pos["quantity"] = new_qty
        self._cash -= int(spend)
        return {"executed": True, "side": "buy", "quantity": qty, "price": price}

    def _sell(self, code: str, krw: int, price: float) -> dict[str, Any]:
        pos = self._positions.get(code)
        if not pos or pos["quantity"] <= 0:
            return {"executed": False, "reason": "no position"}
        target_qty = min(pos["quantity"], krw / price)
        proceeds = int(target_qty * price)
        pos["quantity"] -= target_qty
        self._cash += proceeds
        if pos["quantity"] <= 0:
            pos["avg_buy_price"] = 0.0
        return {"executed": True, "side": "sell", "quantity": target_qty, "price": price}

    def _unrealized_value(self) -> int:
        # 평가금액 계산은 가격 fetcher가 필요하므로 평단가 기준 명목값으로 대체.
        # 정확한 일일 mark-to-market은 scorer에서 별도 계산.
        return int(sum(
            pos["quantity"] * pos["avg_buy_price"]
            for pos in self._positions.values()
        ))
