"""LLM 기반 트리거 생성 어댑터 (트리거 실험용).

지표 룰(detection_engine.RULES) 대신 LLM이 직접 원시 데이터를 분석하여
트리거 발생 여부를 결정한다.

TradingAgents 방식 대응 (우리가 보유한 데이터만 사용):
  Market Analyst      → daily_ohlcv + daily_indicators
  Fundamentals Analyst → daily_fundamentals
  News Analyst        → MongoDB news_articles
  Sentiment Analyst   → MongoDB disclosures

비교 실험:
  모드 A (지표 룰 트리거) vs llm_trigger (LLM 트리거) 수익률 차이 측정.
  decision_fn 은 동일하게 LangGraph 모드 A 사용 — 트리거 생성 단계만 다르다.
"""
from __future__ import annotations

import logging
from datetime import date
from typing import Optional

from pydantic import BaseModel, Field

from ..interfaces import Trigger

logger = logging.getLogger(__name__)

_LLM_RULE_ID = "LLM-TRIGGER"
_DEFAULT_MODEL = "gpt-4o-mini"


# ─── LLM 구조화 출력 스키마 ─────────────────────────────────────────────────

class _TriggerJudgment(BaseModel):
    should_trigger: bool = Field(description="이 종목이 오늘 주목할 만한지 여부")
    signal_direction: str = Field(description="'bullish' | 'bearish' | 'neutral'")
    reason: str = Field(description="트리거 판단 근거 (한국어 1~2문장)")
    confidence: float = Field(ge=0.0, le=1.0, description="판단 신뢰도 0.0~1.0")


# ─── 공개 인터페이스 ─────────────────────────────────────────────────────────

def make_llm_signal_fn(model: str = _DEFAULT_MODEL):
    """signal_generator.detect_all() 와 동일한 시그니처의 함수를 반환.

    LLM이 원시 데이터(기술지표, 펀더멘탈, 뉴스, 공시)를 분석하여
    트리거 발생 여부를 결정한다.

    Args:
        model: 사용할 OpenAI 모델명. 기본 gpt-4o-mini (비용 절감).
    """
    from langchain_openai import ChatOpenAI

    llm = ChatOpenAI(model=model, temperature=0).with_structured_output(_TriggerJudgment)

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
            try:
                trig = _analyze_one_stock(
                    llm=llm,
                    stock_code=stock_code,
                    as_of=as_of,
                    ohlcv=ohlcv_by_stock.get(stock_code) or {},
                    indicators=indicators_by_stock.get(stock_code) or {},
                    fundamentals=fundamentals_by_stock.get(stock_code) or {},
                    disclosures=disclosures_by_stock.get(stock_code) or [],
                    news=news_by_stock.get(stock_code) or [],
                )
                if trig is not None:
                    triggers.append(trig)
            except Exception:
                logger.exception(
                    "llm_signal_fn: 분석 실패 — skip (stock=%s, date=%s)",
                    stock_code, as_of,
                )
        logger.info(
            "llm_signal_fn: %s — watchlist %d종목 중 %d개 트리거",
            as_of, len(watchlist), len(triggers),
        )
        return triggers

    return signal_fn


# ─── 내부 헬퍼 ──────────────────────────────────────────────────────────────

def _analyze_one_stock(
    llm,
    stock_code: str,
    as_of: date,
    ohlcv: dict,
    indicators: dict,
    fundamentals: dict,
    disclosures: list[dict],
    news: list[dict],
) -> Optional[Trigger]:
    """LLM 분석으로 트리거 여부 결정. should_trigger=False 면 None."""
    if not ohlcv and not indicators:
        return None

    prompt = _build_prompt(stock_code, as_of, ohlcv, indicators, fundamentals, disclosures, news)
    judgment: _TriggerJudgment = llm.invoke(prompt)

    if not judgment.should_trigger:
        return None

    return Trigger(
        as_of_date=as_of,
        stock_code=stock_code,
        rule_ids=[_LLM_RULE_ID],
        rule_reasons=[judgment.reason],
        technical=_fmt_technical(ohlcv, indicators) or None,
        fundamental=_fmt_fundamental(fundamentals) or None,
        event=_fmt_event(disclosures) or None,
        sentiment=_fmt_sentiment(news) or None,
        close_price=float(ohlcv["close"]) if ohlcv.get("close") is not None else None,
    )


def _build_prompt(
    stock_code: str,
    as_of: date,
    ohlcv: dict,
    indicators: dict,
    fundamentals: dict,
    disclosures: list[dict],
    news: list[dict],
) -> str:
    """LLM에게 넘길 분석 프롬프트 조립."""
    lines = [
        f"종목코드: {stock_code}  |  기준일: {as_of}",
        "",
        "=== 기술 지표 ===",
        _section_technical(ohlcv, indicators),
        "",
        "=== 펀더멘탈 ===",
        _section_fundamental(fundamentals),
        "",
        "=== 최근 공시 (최대 3건) ===",
        _section_disclosures(disclosures),
        "",
        "=== 최근 뉴스 (최대 3건) ===",
        _section_news(news),
        "",
        "---",
        "위 데이터를 바탕으로 이 종목이 오늘 매매 검토가 필요한지 판단하세요.",
        "데이터가 명확한 방향성(강세 또는 약세 신호)을 보이면 should_trigger=true,",
        "중립적이거나 신호가 불분명하면 should_trigger=false.",
    ]
    return "\n".join(lines)


def _section_technical(ohlcv: dict, ind: dict) -> str:
    close = ohlcv.get("close")
    change = ind.get("return_1d")
    change_str = f"{change*100:+.2f}%" if change is not None else "N/A"
    rsi = ind.get("rsi_14")
    macd = ind.get("macd")
    macd_sig = ind.get("macd_signal")
    sma_align = ind.get("sma_alignment")
    bb_pos = ind.get("bollinger_position")
    mfi = ind.get("mfi_14")

    parts = [
        f"  종가={_fmt_num(close)}  전일대비={change_str}",
        f"  RSI(14)={_fmt_num(rsi)}  MACD={_fmt_num(macd)}  MACD시그널={_fmt_num(macd_sig)}",
        f"  SMA정렬={sma_align or 'N/A'}  볼린저위치={_fmt_num(bb_pos)}  MFI={_fmt_num(mfi)}",
    ]
    return "\n".join(parts)


def _section_fundamental(fund: dict) -> str:
    if not fund:
        return "  데이터 없음"
    parts = [
        f"  PER={_fmt_num(fund.get('per'))}  PBR={_fmt_num(fund.get('pbr'))}  ROE={_fmt_num(fund.get('roe'))}",
        f"  밸류에이션={fund.get('valuation_status','N/A')}  수익성={fund.get('profitability_status','N/A')}",
        f"  성장성={fund.get('growth_status','N/A')}  안정성={fund.get('stability_status','N/A')}",
    ]
    return "\n".join(parts)


def _section_disclosures(docs: list[dict]) -> str:
    if not docs:
        return "  없음"
    items = []
    for d in docs[:3]:
        title = d.get("report_nm") or d.get("title") or ""
        dt = d.get("rcept_dt") or d.get("date") or ""
        items.append(f"  [{dt}] {title}")
    return "\n".join(items)


def _section_news(docs: list[dict]) -> str:
    if not docs:
        return "  없음"
    items = []
    for d in docs[:3]:
        title = d.get("title") or ""
        score = d.get("sentiment_score")
        score_str = f" (감성:{score:+.1f})" if score is not None else ""
        items.append(f"  · {title}{score_str}")
    return "\n".join(items)


def _fmt_num(v) -> str:
    if v is None:
        return "N/A"
    if isinstance(v, float):
        return f"{v:.2f}"
    return str(v)


# ─── Trigger payload 포매터 (signal_generator 와 동일 구조 유지) ─────────────

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
    return {
        "valuation": {
            "per": fund.get("per"), "pbr": fund.get("pbr"),
            "status": fund.get("valuation_status"),
        },
        "profitability": {
            "roe": fund.get("roe"), "status": fund.get("profitability_status"),
        },
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
    if not docs:
        return None
    scores = [d.get("sentiment_score") for d in docs if isinstance(d.get("sentiment_score"), (int, float))]
    if not scores:
        return None
    return {"daily_score": sum(scores) / len(scores), "news_count": len(scores)}
