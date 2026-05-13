"""detection_engine

Signal → 충족된 rule_ids 평가.

각 룰은 `(signal) -> bool` 함수. dict 에 등록된 룰만 평가.
임곗값은 모듈 상단 상수 — 운영 튜닝 편의를 위해 한곳에 모음.

MVP 범위:
  - 구현 13종: RSI-001~004, MACD-001/002, BB-001/002, ATR-001, MFI-001/002,
                PRICE-001/002, VOL-001, SENT-001/002, DART-001
  - 유보 4종: BB-003 (bollinger_position_prev 필요),
              SMA-001~004 (sma_alignment_prev 필요),
              DART-002~005 (공시 5종 sub-categorization 필요)

유보 룰은 candle_collector 가 _prev 필드를 늘리거나 disclosure_collector 가
impact 타입을 세분화한 뒤 추가 — 별도 PR.
"""

import logging
from typing import Optional

from engine.signal_builder import Signal

logger = logging.getLogger(__name__)


# ─── 임곗값 ──────────────────────────────────────────────────────────────────
# 운영 중 튜닝 가능. architecture 문서가 구체값 명시 안 한 항목은 일반적
# 매매 룰 디폴트로 시작 (전문가 백테스트 결과로 보정 예정).

# RSI
RSI_OVERBOUGHT_LINE = 70.0   # RSI-001 상태 & RSI-003 교차 기준
RSI_OVERSOLD_LINE   = 30.0   # RSI-002 상태 & RSI-004 교차 기준

# MFI
MFI_OVERBOUGHT_LINE = 80.0   # MFI-001
MFI_OVERSOLD_LINE   = 20.0   # MFI-002

# ATR (변동성 폭발 — atr_ratio = atr/close)
ATR_SPIKE_RATIO = 0.05       # ATR-001, 5%

# PRICE (일간 변화율)
PRICE_SPIKE_UP_PCT   = 7.0    # PRICE-001
PRICE_SPIKE_DOWN_PCT = -7.0   # PRICE-002

# SENT (FinBERT daily_score 가 0~1 스케일이면 임곗값 0.7 / 0.3, -1~1 스케일이면 ±0.7)
# news_collector 의 sentiment 출력 스케일 확정되면 한쪽으로 맞춤 — 일단 0~1 기준.
SENT_POSITIVE_LINE = 0.7      # SENT-001
SENT_NEGATIVE_LINE = 0.3      # SENT-002


# ─── 안전 접근 헬퍼 ─────────────────────────────────────────────────────────

def _get(signals: Optional[dict], *path: str):
    """signals 에서 path 따라 깊이 들어가기. 중간이 None 이거나 키 없으면 None.

    사용: _get(signal.signals, "technical", "momentum", "rsi_14")
    """
    d = signals
    for k in path:
        if not isinstance(d, dict):
            return None
        d = d.get(k)
    return d


# ─── 룰 함수들 ──────────────────────────────────────────────────────────────

# RSI-001: 과매수 (rsi_14 >= 70)
def _rule_rsi_001(s: Signal) -> bool:
    rsi = _get(s.signals, "technical", "momentum", "rsi_14")
    return rsi is not None and rsi >= RSI_OVERBOUGHT_LINE


# RSI-002: 과매도 (rsi_14 <= 30)
def _rule_rsi_002(s: Signal) -> bool:
    rsi = _get(s.signals, "technical", "momentum", "rsi_14")
    return rsi is not None and rsi <= RSI_OVERSOLD_LINE


# RSI-003: 70 상향 돌파 (prev < 70 AND now >= 70)
def _rule_rsi_003(s: Signal) -> bool:
    now = _get(s.signals, "technical", "momentum", "rsi_14")
    prev = _get(s.signals, "technical", "momentum", "rsi_14_prev")
    return (now is not None and prev is not None
            and prev < RSI_OVERBOUGHT_LINE <= now)


# RSI-004: 30 하향 이탈 (prev > 30 AND now <= 30)
def _rule_rsi_004(s: Signal) -> bool:
    now = _get(s.signals, "technical", "momentum", "rsi_14")
    prev = _get(s.signals, "technical", "momentum", "rsi_14_prev")
    return (now is not None and prev is not None
            and prev > RSI_OVERSOLD_LINE >= now)


# MACD-001: 골든크로스 (macd_state == bullish_cross)
def _rule_macd_001(s: Signal) -> bool:
    return _get(s.signals, "technical", "trend", "macd_state") == "bullish_cross"


# MACD-002: 데드크로스 (macd_state == bearish_cross)
def _rule_macd_002(s: Signal) -> bool:
    return _get(s.signals, "technical", "trend", "macd_state") == "bearish_cross"


# BB-001: 상단 이탈 (bollinger_position == upper_breakout)
def _rule_bb_001(s: Signal) -> bool:
    return _get(s.signals, "technical", "volatility", "bollinger_position") == "upper_breakout"


# BB-002: 하단 이탈 (bollinger_position == lower_breakout)
def _rule_bb_002(s: Signal) -> bool:
    return _get(s.signals, "technical", "volatility", "bollinger_position") == "lower_breakout"


# ATR-001: 변동성 폭발 (atr_ratio >= 5%)
def _rule_atr_001(s: Signal) -> bool:
    ratio = _get(s.signals, "technical", "volatility", "atr_ratio")
    return ratio is not None and ratio >= ATR_SPIKE_RATIO


# MFI-001: 과매수 (mfi_14 >= 80)
def _rule_mfi_001(s: Signal) -> bool:
    mfi = _get(s.signals, "technical", "volume", "mfi_14")
    return mfi is not None and mfi >= MFI_OVERBOUGHT_LINE


# MFI-002: 과매도 (mfi_14 <= 20)
def _rule_mfi_002(s: Signal) -> bool:
    mfi = _get(s.signals, "technical", "volume", "mfi_14")
    return mfi is not None and mfi <= MFI_OVERSOLD_LINE


# PRICE-001: 급등 (change_pct >= 7%)
def _rule_price_001(s: Signal) -> bool:
    change = _get(s.signals, "technical", "snapshot", "change_pct")
    return change is not None and change >= PRICE_SPIKE_UP_PCT


# PRICE-002: 급락 (change_pct <= -7%)
def _rule_price_002(s: Signal) -> bool:
    change = _get(s.signals, "technical", "snapshot", "change_pct")
    return change is not None and change <= PRICE_SPIKE_DOWN_PCT


# VOL-001: 거래량 급증 (candle_collector 가 boolean 으로 계산해둠)
def _rule_vol_001(s: Signal) -> bool:
    return bool(_get(s.signals, "technical", "snapshot", "volume_spike"))


# SENT-001: 긍정 강도 (daily_score >= 0.7)
def _rule_sent_001(s: Signal) -> bool:
    score = _get(s.signals, "sentiment", "daily_score")
    return score is not None and score >= SENT_POSITIVE_LINE


# SENT-002: 부정 강도 (daily_score <= 0.3)
def _rule_sent_002(s: Signal) -> bool:
    score = _get(s.signals, "sentiment", "daily_score")
    return score is not None and score <= SENT_NEGATIVE_LINE


# DART-001: 긴급 공시 (has_urgent_issue == True)
# DART-002 ~ 005 는 공시 5종 sub-classification 확정 후 추가.
def _rule_dart_001(s: Signal) -> bool:
    return bool(_get(s.signals, "event", "has_urgent_issue"))


# ─── 룰 레지스트리 ──────────────────────────────────────────────────────────

# rule_id → check function. 추가 시 여기에만 등록하면 detect 가 자동 적용.
RULES: dict[str, callable] = {
    "RSI-001":   _rule_rsi_001,
    "RSI-002":   _rule_rsi_002,
    "RSI-003":   _rule_rsi_003,
    "RSI-004":   _rule_rsi_004,
    "MACD-001":  _rule_macd_001,
    "MACD-002":  _rule_macd_002,
    "BB-001":    _rule_bb_001,
    "BB-002":    _rule_bb_002,
    "ATR-001":   _rule_atr_001,
    "MFI-001":   _rule_mfi_001,
    "MFI-002":   _rule_mfi_002,
    "PRICE-001": _rule_price_001,
    "PRICE-002": _rule_price_002,
    "VOL-001":   _rule_vol_001,
    "SENT-001":  _rule_sent_001,
    "SENT-002":  _rule_sent_002,
    "DART-001":  _rule_dart_001,
}


# rule_id → 한국어 사유. ai_agent MarketTriggerEvent.trigger.trigger_reason 에 들어감.
# RULES 와 같은 인덱스로 매핑 — 새 룰 추가 시 두 dict 동시 갱신.
RULE_REASONS: dict[str, str] = {
    "RSI-001":   "RSI 과매수",
    "RSI-002":   "RSI 과매도",
    "RSI-003":   "RSI 70 상향 돌파",
    "RSI-004":   "RSI 30 하향 이탈",
    "MACD-001":  "MACD 골든크로스",
    "MACD-002":  "MACD 데드크로스",
    "BB-001":    "볼린저밴드 상단 이탈",
    "BB-002":    "볼린저밴드 하단 이탈",
    "ATR-001":   "변동성 급증",
    "MFI-001":   "MFI 과매수",
    "MFI-002":   "MFI 과매도",
    "PRICE-001": "급등",
    "PRICE-002": "급락",
    "VOL-001":   "거래량 급증",
    "SENT-001":  "긍정 뉴스 강세",
    "SENT-002":  "부정 뉴스 강세",
    "DART-001":  "긴급 공시",
}


# RULES ↔ RULE_REASONS 키셋 드리프트 차단 — 룰 추가 시 사유 누락하면 모듈 로드 자체가 실패.
# event_publisher._build_payload 의 .get(rid, rid) fallback 은 production -O 모드 등 만일의 안전망.
_RULE_KEY_MISMATCH = set(RULES) ^ set(RULE_REASONS)
if _RULE_KEY_MISMATCH:
    raise RuntimeError(
        f"RULES / RULE_REASONS 키셋 불일치: {sorted(_RULE_KEY_MISMATCH)}. "
        "두 dict 를 동시에 갱신해야 합니다."
    )


def detect(signal: Signal) -> list[str]:
    """Signal → 충족된 rule_ids 리스트. 룰 평가는 등록 순서.

    각 룰은 isolated — 한 룰 평가 중 예외가 나도 다른 룰엔 영향 없게 catch.
    """
    triggered: list[str] = []
    for rule_id, check in RULES.items():
        try:
            if check(signal):
                triggered.append(rule_id)
        except Exception:
            logger.exception("rule %s check failed (stock=%s)", rule_id, signal.stock_code)
    return triggered
