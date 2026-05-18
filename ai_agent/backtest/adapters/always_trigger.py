"""TradingAgents 방식 — 트리거 선별 없이 매일 전 종목 LangGraph 진입.

기존 모드 A: 지표 룰 → 일부 종목만 트리거 → LangGraph
이 모드:    선별 없음 → 전 종목 매일 트리거 → LangGraph (HOLD = 사실상 패스)

목적: TradingAgents 방식과 우리 방식의 토큰 사용량 비교.
     LangSmith에서 두 run의 total_tokens를 비교하면 비용 차이를 측정할 수 있다.
"""
from __future__ import annotations

import logging
from datetime import date

from ..interfaces import Trigger

logger = logging.getLogger(__name__)

_RULE_ID = "DAILY-SCAN"
_RULE_REASON = "전 종목 일일 분석 (TradingAgents 방식)"


def make_always_trigger_signal_fn():
    """watchlist 전 종목을 매일 무조건 트리거로 변환하는 signal_fn 반환.

    실제 데이터(ohlcv, indicators 등)는 그대로 Trigger payload에 담아
    LangGraph decision_fn이 정상적으로 판단할 수 있게 한다.
    """
    def signal_fn(
        as_of: date,
        watchlist: list[str],
        ohlcv_by_stock: dict,
        indicators_by_stock: dict,
        fundamentals_by_stock: dict,
        disclosures_by_stock: dict,
        news_by_stock: dict,
    ) -> list[Trigger]:
        triggers: list[Trigger] = []
        for stock_code in watchlist:
            ohlcv = ohlcv_by_stock.get(stock_code) or {}
            ind = indicators_by_stock.get(stock_code) or {}
            fund = fundamentals_by_stock.get(stock_code) or {}
            disclosures = disclosures_by_stock.get(stock_code) or []
            news = news_by_stock.get(stock_code) or []

            # ohlcv 데이터 없으면 skip (상장폐지·거래정지 종목 방어)
            if not ohlcv:
                continue

            triggers.append(Trigger(
                as_of_date=as_of,
                stock_code=stock_code,
                rule_ids=[_RULE_ID],
                rule_reasons=[_RULE_REASON],
                technical=_fmt_technical(ohlcv, ind) or None,
                fundamental=_fmt_fundamental(fund) or None,
                event=_fmt_event(disclosures),
                sentiment=_fmt_sentiment(news),
                close_price=float(ohlcv["close"]) if ohlcv.get("close") is not None else None,
            ))

        logger.info("always_trigger: %s — 전 종목 %d개 트리거", as_of, len(triggers))
        return triggers

    return signal_fn


def _fmt_technical(ohlcv: dict, ind: dict) -> dict:
    return {
        "snapshot": {
            "open": ohlcv.get("open"), "high": ohlcv.get("high"),
            "low": ohlcv.get("low"), "close": ohlcv.get("close"),
            "volume": ohlcv.get("volume"),
            "change_pct": ind.get("return_1d") * 100 if ind.get("return_1d") is not None else None,
        },
        "trend": {
            "sma_5": ind.get("sma_5"), "sma_20": ind.get("sma_20"),
            "sma_60": ind.get("sma_60"), "sma_alignment": ind.get("sma_alignment"),
            "macd": ind.get("macd"), "macd_signal": ind.get("macd_signal"),
            "macd_state": ind.get("macd_state"),
        },
        "momentum": {
            "rsi_14": ind.get("rsi_14"), "rsi_14_prev": ind.get("rsi_14_prev"),
        },
        "volatility": {
            "bb_upper": ind.get("bb_upper"), "bb_lower": ind.get("bb_lower"),
            "bollinger_position": ind.get("bollinger_position"),
            "atr": ind.get("atr"), "atr_ratio": ind.get("atr_ratio"),
        },
        "volume": {
            "mfi_14": ind.get("mfi_14"), "mfi_14_prev": ind.get("mfi_14_prev"),
        },
    }


def _fmt_fundamental(fund: dict) -> dict:
    if not fund:
        return {}
    return {
        "valuation": {"per": fund.get("per"), "pbr": fund.get("pbr"), "status": fund.get("valuation_status")},
        "profitability": {"roe": fund.get("roe"), "status": fund.get("profitability_status")},
        "growth": {
            "revenue_growth": fund.get("revenue_growth"),
            "operating_growth": fund.get("operating_growth"),
            "status": fund.get("growth_status"),
        },
        "stability": {
            "debt_ratio": fund.get("debt_ratio"),
            "current_ratio": fund.get("current_ratio"),
            "status": fund.get("stability_status"),
        },
    }


def _fmt_event(docs: list[dict]) -> dict | None:
    if not docs:
        return None
    return {"disclosures": [
        {"date": d.get("rcept_dt") or d.get("date"), "title": d.get("report_nm") or d.get("title")}
        for d in docs[:5]
    ]}


def _fmt_sentiment(docs: list[dict]) -> dict | None:
    """news_collector.update_sentiment_redis() 집계 재현 — window 평균, Redis 포맷."""
    if not docs:
        return None
    valid = [
        d for d in docs
        if isinstance(d.get("sentiment_score"), (int, float))
        and d.get("sentiment_confidence") is not None
    ]
    if not valid:
        scores = [d["sentiment_score"] for d in docs
                  if isinstance(d.get("sentiment_score"), (int, float))]
        if not scores:
            return None
        return {"daily_score": round(sum(scores) / len(scores), 2),
                "confidence_level": None, "article_count": len(scores)}
    n = len(valid)
    avg_score = sum(d["sentiment_score"]               for d in valid) / n
    avg_conf  = sum(d["sentiment_confidence"]          for d in valid) / n
    avg_pos   = sum(d.get("sentiment_pos_prob") or 0   for d in valid) / n
    avg_neu   = sum(d.get("sentiment_neu_prob") or 0   for d in valid) / n
    avg_neg   = sum(d.get("sentiment_neg_prob") or 0   for d in valid) / n

    def _conf_level(c: float) -> str:
        return "high" if c >= 70 else ("medium" if c >= 30 else "low")

    return {
        "daily_score":      round(avg_score, 2),
        "confidence_level": _conf_level(avg_conf),
        "pos_prob":         round(avg_pos, 2),
        "neu_prob":         round(avg_neu, 2),
        "neg_prob":         round(avg_neg, 2),
        "article_count":    n,
    }
