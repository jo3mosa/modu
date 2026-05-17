"""equity_curve 기반 포트폴리오 성과 메트릭 산출.

`SimplePortfolio.mark_to_market`이 매일 쌓는 equity_curve(list[dict])를 받아
CAGR / Sharpe / Sortino / Calmar / MaxDD 등 표준 메트릭을 계산한다.

KIS backtester(`open-trading-api/backtester`)는 QuantConnect Lean이 동일 메트릭을
자동 산출하지만, MODU는 자작 event_loop 기반이라 이 layer를 직접 만든다.

설계 원칙:
  - 외부 의존성 0 (math/statistics만 사용) — numpy/pandas 없이도 동작
  - warmup(거래 0인 기간)을 first_trade_date로 마킹 → Sharpe 부풀림 진단 가능
  - 모든 비율은 0.07(=7%) 형태 (퍼센트가 아닌 비율)
"""
from __future__ import annotations

import math
from datetime import date, datetime
from typing import Optional

# 한국 주식시장 기준 영업일/년 (KOSPI 평년 약 246~250일, 통상 252로 사용)
TRADING_DAYS_PER_YEAR = 252
DEFAULT_RISK_FREE_RATE = 0.03  # 연 3% — 한국 단기국채 통상 수준


def compute_equity_metrics(
    equity_curve: list[dict],
    *,
    risk_free_rate: float = DEFAULT_RISK_FREE_RATE,
    trading_days_per_year: int = TRADING_DAYS_PER_YEAR,
) -> dict:
    """일별 자산 곡선을 받아 표준 성과 메트릭을 dict로 반환.

    Args:
        equity_curve: SimplePortfolio.mark_to_market가 쌓은 리스트.
            각 원소: {"date": "YYYY-MM-DD", "equity": float, "cash": float, ...}
        risk_free_rate: 연 무위험 이자율 (Sharpe/Sortino 계산용).
        trading_days_per_year: 연간 거래일 수 (annualization 인자).

    Returns:
        dict — 비어 있거나 데이터 부족 시 0으로 채워진 dict 반환.

    주의:
      - Sharpe는 warmup 기간이 길수록 과대계상됨 (idle 일자가 표준편차를 낮춤).
        KIS README 경고와 동일. first_trade_date를 별도로 노출하므로 그 시점
        이후로 재산정하고 싶으면 `first_trade_date` 키 이후만 slice 후 재호출.
      - CAGR 분모는 전체 기간 (warmup 포함). 거래가 없는 구간이 길면 분모 왜곡.
    """
    if not equity_curve or len(equity_curve) < 2:
        return _empty_metrics()

    sorted_curve = sorted(equity_curve, key=lambda r: str(r.get("date") or ""))
    equities = [float(r.get("equity") or 0.0) for r in sorted_curve]
    dates = [_parse_date(r.get("date")) for r in sorted_curve]

    start_equity = equities[0]
    end_equity = equities[-1]
    if start_equity <= 0:
        return _empty_metrics()

    total_return_pct = (end_equity - start_equity) / start_equity

    # 일별 수익률 — 0/0 방지
    daily_returns: list[float] = []
    for i in range(1, len(equities)):
        prev = equities[i - 1]
        if prev <= 0:
            daily_returns.append(0.0)
        else:
            daily_returns.append((equities[i] - prev) / prev)

    # first_trade_date — 일별 수익률이 0이 아닌 첫 날 (warmup 종료점)
    first_trade_idx = _find_first_trade_index(daily_returns)
    first_trade_date = dates[first_trade_idx + 1] if first_trade_idx is not None and first_trade_idx + 1 < len(dates) else None

    # CAGR — 달력일 기준. dates가 datetime이면 그대로, 아니면 거래일 비례 환산.
    start_d, end_d = dates[0], dates[-1]
    if start_d and end_d and end_d > start_d:
        years = (end_d - start_d).days / 365.25
    else:
        years = len(equities) / trading_days_per_year
    cagr = ((end_equity / start_equity) ** (1 / years) - 1) if years > 0 else 0.0

    sharpe = _sharpe_ratio(daily_returns, risk_free_rate, trading_days_per_year)
    sortino = _sortino_ratio(daily_returns, risk_free_rate, trading_days_per_year)
    max_dd_pct, max_dd_krw = _max_drawdown(equities)
    calmar = (cagr / max_dd_pct) if max_dd_pct > 0 else 0.0

    return {
        "n_days": len(equities),
        "start_date": dates[0].isoformat() if dates[0] else None,
        "end_date": dates[-1].isoformat() if dates[-1] else None,
        "first_trade_date": first_trade_date.isoformat() if first_trade_date else None,
        "start_equity": start_equity,
        "end_equity": end_equity,
        "total_return": end_equity - start_equity,
        "total_return_pct": total_return_pct,
        "cagr": cagr,
        "sharpe": sharpe,
        "sortino": sortino,
        "calmar": calmar,
        "max_drawdown_pct": max_dd_pct,
        "max_drawdown_krw": max_dd_krw,
        "risk_free_rate": risk_free_rate,
        "trading_days_per_year": trading_days_per_year,
    }


def _empty_metrics() -> dict:
    return {
        "n_days": 0, "start_date": None, "end_date": None, "first_trade_date": None,
        "start_equity": 0.0, "end_equity": 0.0,
        "total_return": 0.0, "total_return_pct": 0.0,
        "cagr": 0.0, "sharpe": 0.0, "sortino": 0.0, "calmar": 0.0,
        "max_drawdown_pct": 0.0, "max_drawdown_krw": 0.0,
        "risk_free_rate": DEFAULT_RISK_FREE_RATE,
        "trading_days_per_year": TRADING_DAYS_PER_YEAR,
    }


def _parse_date(value) -> Optional[date]:
    if value is None:
        return None
    if isinstance(value, date) and not isinstance(value, datetime):
        return value
    if isinstance(value, datetime):
        return value.date()
    try:
        return date.fromisoformat(str(value))
    except (ValueError, TypeError):
        return None


def _find_first_trade_index(daily_returns: list[float]) -> Optional[int]:
    """일별 수익률 시퀀스에서 처음으로 0이 아닌 인덱스를 반환."""
    for i, r in enumerate(daily_returns):
        if abs(r) > 1e-12:
            return i
    return None


def _sharpe_ratio(daily_returns: list[float], rf_annual: float, days_per_year: int) -> float:
    """연율화 Sharpe = (E[r] - rf_daily) / std(r) * sqrt(days_per_year)."""
    if len(daily_returns) < 2:
        return 0.0
    rf_daily = rf_annual / days_per_year
    mean = sum(daily_returns) / len(daily_returns)
    var = sum((r - mean) ** 2 for r in daily_returns) / (len(daily_returns) - 1)
    std = math.sqrt(var)
    if std <= 0:
        return 0.0
    return (mean - rf_daily) / std * math.sqrt(days_per_year)


def _sortino_ratio(daily_returns: list[float], rf_annual: float, days_per_year: int) -> float:
    """연율화 Sortino = (E[r] - rf_daily) / downside_std * sqrt(days_per_year).

    downside_std = sqrt(mean(min(r - rf_daily, 0)^2)).
    """
    if len(daily_returns) < 2:
        return 0.0
    rf_daily = rf_annual / days_per_year
    mean = sum(daily_returns) / len(daily_returns)
    downside_sq = [(min(r - rf_daily, 0.0)) ** 2 for r in daily_returns]
    downside_var = sum(downside_sq) / len(downside_sq)
    downside_std = math.sqrt(downside_var)
    if downside_std <= 0:
        return 0.0
    return (mean - rf_daily) / downside_std * math.sqrt(days_per_year)


def _max_drawdown(equities: list[float]) -> tuple[float, float]:
    """누적 최대낙폭. (비율, KRW절댓값) 반환.

    peak 갱신하며 (peak - cur) / peak의 최대값 추적.
    """
    if not equities:
        return 0.0, 0.0
    peak = equities[0]
    max_dd_pct = 0.0
    max_dd_krw = 0.0
    for eq in equities:
        if eq > peak:
            peak = eq
        if peak > 0:
            dd_pct = (peak - eq) / peak
            dd_krw = peak - eq
            if dd_pct > max_dd_pct:
                max_dd_pct = dd_pct
                max_dd_krw = dd_krw
    return max_dd_pct, max_dd_krw
