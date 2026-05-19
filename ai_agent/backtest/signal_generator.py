"""백테스트 트리거 생성 — analysis_server engine 룰을 시점 기반으로 재현.

핵심:
  - detection_engine.detect() / RULES / RULE_REASONS 를 그대로 import → 룰 정의가
    한 곳에 있음 보장 (운영·백테스트 분기 위험 차단).
  - signal_builder.Signal 자료구조도 그대로 import → detect() 가 받는 형식 일치.
  - signal_builder.build() 는 Redis live 의존이라 재구현 — 이 모듈의 build_signal()
    이 4 source(technical/fundamental/event/sentiment) payload 를 bulk fetch 결과로
    구성한 뒤 Signal 인스턴스를 만들어 detect() 에 넘긴다.

analysis_server 모듈을 import 하기 위해 모노레포 sibling 경로를 sys.path 에 추가.
운영 패키지화는 별도 작업 — 백테스트는 dev tool 이라 hack 허용.
"""

from __future__ import annotations

import logging
import sys
from datetime import date, datetime
from pathlib import Path
from typing import Optional
from zoneinfo import ZoneInfo

# ─── analysis_server 모듈 import 부트스트랩 ──────────────────────────────────
# modu/ai_agent/backtest/signal_generator.py → modu/analysis_server
_ANALYSIS_SERVER = Path(__file__).resolve().parents[2] / "analysis_server"
if str(_ANALYSIS_SERVER) not in sys.path:
    sys.path.insert(0, str(_ANALYSIS_SERVER))

from collectors.disclosure_collector import build_event_payload   # noqa: E402
from engine.detection_engine import RULE_REASONS, RULES, detect   # noqa: E402
from engine.event_publisher import _summarize_news                # noqa: E402
from engine.signal_builder import Signal                          # noqa: E402

from . import config
from .interfaces import Trigger

logger = logging.getLogger(__name__)
KST = ZoneInfo("Asia/Seoul")


# ─── 4 source payload 빌더 ───────────────────────────────────────────────────
# analysis_server 의 candle_collector.compute_indicators / signal_builder
# ._load_fundamental_from_db / disclosure_collector.build_event_payload 가
# live 환경에서 만들어내는 schema 와 동일 모양을 시점 데이터로 구성한다.

def _to_technical(ind_row: dict, ohlc_row: Optional[dict]) -> Optional[dict]:
    """daily_indicators row + daily_ohlcv row → technical payload.

    candle_collector.compute_indicators() 반환 형식과 동일:
      snapshot / trend / momentum / volatility / volume.
    daily_indicators 에 _prev 필드가 병합돼 있어야 RSI-003/004 평가 가능.
    """
    if not ind_row:
        return None
    # return_1d 단위가 비율(0.07)인지 % 인지 DDL 주석 추정으로 비율 가정 → *100.
    # 다른 단위면 daily_indicators 적재 단에서 통일 필요.
    return {
        "snapshot": {
            "open":         ohlc_row.get("open") if ohlc_row else None,
            "high":         ohlc_row.get("high") if ohlc_row else None,
            "low":          ohlc_row.get("low") if ohlc_row else None,
            "close":        ohlc_row.get("close") if ohlc_row else None,
            "volume":       ohlc_row.get("volume") if ohlc_row else None,
            "change_pct":   _maybe_pct(ind_row.get("return_1d")),
            # volume_spike = (today volume) > 2.0 × (직전 20일 평균 volume).
            # compute_volume_spike 가 daily_indicators 에 사전 계산.
            # VOL-001 / TPL-001 / TPL-002 / TPL-003 평가에 사용.
            "volume_spike": ind_row.get("volume_spike"),
        },
        "trend": {
            "sma_5":         ind_row.get("sma_5"),
            "sma_20":        ind_row.get("sma_20"),
            "sma_60":        ind_row.get("sma_60"),
            "sma_alignment": ind_row.get("sma_alignment"),
            "macd":          ind_row.get("macd"),
            "macd_signal":   ind_row.get("macd_signal"),
            "macd_state":    ind_row.get("macd_state"),
        },
        "momentum": {
            "rsi_14":      ind_row.get("rsi_14"),
            "rsi_14_prev": ind_row.get("rsi_14_prev"),
        },
        "volatility": {
            "bb_upper":           ind_row.get("bb_upper"),
            "bb_lower":           ind_row.get("bb_lower"),
            "bollinger_position": ind_row.get("bollinger_position"),
            "atr":                ind_row.get("atr"),
            "atr_ratio":          ind_row.get("atr_ratio"),
        },
        "volume": {
            "mfi_14":      ind_row.get("mfi_14"),
            "mfi_14_prev": ind_row.get("mfi_14_prev"),
        },
    }


def _maybe_pct(v: Optional[float]) -> Optional[float]:
    """0.07 → 7.0. None passthrough. 이미 %(7.0) 단위면 결과가 700.0 이 되어 룰
    임계 미발화가 명백한 노이즈가 됨 — 단위가 의심되면 daily_indicators 적재 단을
    먼저 확인하라는 신호."""
    return None if v is None else v * 100


def _to_fundamental(fund_row: Optional[dict]) -> Optional[dict]:
    """daily_fundamentals row → fundamental payload.

    signal_builder._load_fundamental_from_db 와 동일 schema.
    """
    if not fund_row:
        return None
    return {
        "valuation": {
            "per":    fund_row.get("per"),
            "pbr":    fund_row.get("pbr"),
            "status": fund_row.get("valuation_status"),
        },
        "profitability": {
            "roe":          fund_row.get("roe"),
            # cross-sectional ROE percentile rank (0=최상위, 1=최하위).
            # compute_fundamental_ranks 가 사전 계산. QUAL-001 평가에 사용.
            "roe_rank_pct": fund_row.get("roe_rank_pct"),
            "status":       fund_row.get("profitability_status"),
        },
        "growth": {
            "revenue_growth":   fund_row.get("revenue_growth"),
            "operating_growth": fund_row.get("operating_growth"),
            "status":           fund_row.get("growth_status"),
        },
        "stability": {
            "debt_ratio":    fund_row.get("debt_ratio"),
            "current_ratio": fund_row.get("current_ratio"),
            "status":        fund_row.get("stability_status"),
        },
    }


def _to_event(disclosure_docs: Optional[list[dict]]) -> Optional[dict]:
    """Mongo disclosures 문서 리스트 → event payload.

    disclosure_collector.build_event_payload 를 그대로 재사용 — 단,
    그 함수는 DART 원본 dict(rcept_dt/report_nm 키) 를 기대. Mongo 적재 시
    `raw` 필드에 원본을 보존했으므로 그걸 풀어 넘겨준다.
    """
    if not disclosure_docs:
        return None
    raw_docs = [d.get("raw") or {
        # 보조: raw 누락 시 top-level 필드로 임시 합성
        "rcept_dt": d.get("rcept_dt", ""),
        "report_nm": d.get("report_nm", ""),
        "stock_code": d.get("stock_code", ""),
    } for d in disclosure_docs]
    return build_event_payload(raw_docs)


def _to_sentiment(news_docs: Optional[list[dict]]) -> Optional[dict]:
    """뉴스 문서 → sentiment payload.

    news_collector 의 라이브 sentiment:{stock} schema 와 정합:
      {daily_score, confidence_level, pos_prob, neu_prob, neg_prob}
    뉴스가 없거나 sentiment_score 필드 누락이면 None — SENT-001/002 미발화.

    daily_score 는 그날 매칭된 모든 뉴스의 sentiment_score 평균(-100~100 스케일).
    AI 팀이 다른 집계법(가중 평균 등) 을 원하면 이 함수만 교체하면 됨.
    """
    if not news_docs:
        return None
    scores = [d.get("sentiment_score") for d in news_docs
              if isinstance(d.get("sentiment_score"), (int, float))]
    if not scores:
        return None
    avg = sum(scores) / len(scores)
    return {
        "daily_score":      avg,
        "confidence_level": None,
        "news_count":       len(scores),
    }


# ─── 트리거 빌드 + 검출 ──────────────────────────────────────────────────────

def build_trigger(
    as_of: date,
    stock_code: str,
    ind_row: Optional[dict],
    ohlc_row: Optional[dict],
    fund_row: Optional[dict],
    disclosure_docs: Optional[list[dict]],
    news_docs: Optional[list[dict]],
) -> Optional[Trigger]:
    """한 종목 × 한 시점 → Trigger. 룰 발화 없으면 None.

    detect() 가 빈 리스트를 반환하면 트리거 자체를 생성하지 않음 —
    event_loop 에서 빈 트리거를 의사결정에 흘리지 않게 한다.
    """
    technical   = _to_technical(ind_row, ohlc_row)
    fundamental = _to_fundamental(fund_row)
    event       = _to_event(disclosure_docs)
    sentiment   = _to_sentiment(news_docs)

    signal = Signal(
        stock_code=stock_code,
        timestamp=datetime.combine(as_of, datetime.min.time(), tzinfo=KST),
        signals={
            "technical":   technical,
            "fundamental": fundamental,
            "event":       event,
            "sentiment":   sentiment,
        },
    )
    rule_ids = detect(signal)
    if not rule_ids:
        return None

    # 실서비스와 동일하게 rule_ids 기반 윈도우로 Mongo 재조회 + LLM 요약.
    # pick_news_window(rule_ids)가 가장 긴 윈도우를 선택 → agent가 충분한 컨텍스트 확보.
    news_summary = _summarize_news(stock_code, rule_ids, signal)

    return Trigger(
        as_of_date=as_of,
        stock_code=stock_code,
        rule_ids=rule_ids,
        rule_reasons=[RULE_REASONS.get(rid, rid) for rid in rule_ids],
        technical=technical,
        fundamental=fundamental,
        event=event,
        sentiment=sentiment,
        close_price=ohlc_row.get("close") if ohlc_row else None,
        news_summary=news_summary,
    )


def detect_all(
    as_of: date,
    watchlist: list[str],
    ohlcv_by_stock: dict[str, dict],
    indicators_by_stock: dict[str, dict],
    fundamentals_by_stock: dict[str, dict],
    disclosures_by_stock: dict[str, list[dict]],
    news_by_stock: dict[str, list[dict]],
) -> list[Trigger]:
    """as_of 시점에 watchlist 전 종목을 평가해 발화된 트리거만 모아 반환.

    빈 입력(누락된 종목) 은 None payload 로 흘려보내 detection_engine 의 safe-access
    로직이 자연스럽게 미발화 처리하게 한다.
    """
    triggers: list[Trigger] = []
    for stock_code in watchlist:
        trig = build_trigger(
            as_of=as_of,
            stock_code=stock_code,
            ind_row=indicators_by_stock.get(stock_code),
            ohlc_row=ohlcv_by_stock.get(stock_code),
            fund_row=fundamentals_by_stock.get(stock_code),
            disclosure_docs=disclosures_by_stock.get(stock_code),
            news_docs=news_by_stock.get(stock_code),
        )
        if trig is not None:
            triggers.append(trig)
    return triggers


# RULES / RULE_REASONS 재노출 — AI 팀이 mock decision 작성할 때 import 편의.
__all__ = ["build_trigger", "detect_all", "RULES", "RULE_REASONS"]
