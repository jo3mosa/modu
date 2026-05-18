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
from datetime import timedelta
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

# SENT (FinBERT 의 sentiment_score 스케일 -100~100 — pos_prob - neg_prob 에
# confidence (1 - neu_prob) 가중. 강한 방향성 + 낮은 중립이어야 ±60 통과).
# 보수적 60 으로 시작 — 운영 백테스트 결과로 조정 (낮추면 발화 잦아짐).
SENT_POSITIVE_LINE = 60.0     # SENT-001
SENT_NEGATIVE_LINE = -60.0    # SENT-002

# QUAL-001: ROE 상위 N% 풀. roe_rank_pct 0=최상위, 1=최하위.
# 백테스트 B35 이 0.20 으로 통과. compute_fundamental_ranks 가 사전 계산.
ROE_TOP_PERCENTILE = 0.20


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


# ─── 조합 트리거 (백테스트 채택 11개) ──────────────────────────────────────
#
# 모두 state 기반 (현재값만 검사). cooldown 으로 transition 효과 재현 —
# REV/TPL/EVT 24h, QUAL 48h. cooldown_manager.COOLDOWN_TTL_SECONDS 참조.
#
# 매핑 (live ID ↔ 백테스트 가설 ID):
#   REV-001 ↔ B1   이중 과매도 (RSI<30 & MFI<20)
#   REV-002 ↔ B2   RSI<30 & 볼린저 하단
#   REV-003 ↔ B4   이중 과매수 (RSI>70 & MFI>80)
#   REV-004 ↔ B5   RSI>70 & 볼린저 상단
#   REV-005 ↔ B6   MFI>80 & 볼린저 상단
#   QUAL-001 ↔ B35  ROE 상위 풀 ∩ RSI<30 (Fama-French Quality)
#   EVT-001 ↔ B28  호재공시 & RSI>70 (모멘텀 가속)
#   TPL-001 ↔ C2   RSI>70 & MFI>80 & 거래량 급증
#   TPL-002 ↔ C3   RSI<30 & 볼린저 하단 & 거래량 급증 (패닉 바닥)
#   TPL-003 ↔ C4   RSI>70 & 볼린저 상단 & 거래량 급증
#   TPL-004 ↔ C6   RSI>70 & MFI>80 & 볼린저 상단


# REV-001: RSI<30 & MFI<20 (B1)
def _rule_rev_001(s: Signal) -> bool:
    rsi = _get(s.signals, "technical", "momentum", "rsi_14")
    mfi = _get(s.signals, "technical", "volume", "mfi_14")
    return (rsi is not None and rsi <= RSI_OVERSOLD_LINE
            and mfi is not None and mfi <= MFI_OVERSOLD_LINE)


# REV-002: RSI<30 & 볼린저 하단 (B2)
def _rule_rev_002(s: Signal) -> bool:
    rsi = _get(s.signals, "technical", "momentum", "rsi_14")
    bb_pos = _get(s.signals, "technical", "volatility", "bollinger_position")
    return (rsi is not None and rsi <= RSI_OVERSOLD_LINE
            and bb_pos == "lower_breakout")


# REV-003: RSI>70 & MFI>80 (B4)
def _rule_rev_003(s: Signal) -> bool:
    rsi = _get(s.signals, "technical", "momentum", "rsi_14")
    mfi = _get(s.signals, "technical", "volume", "mfi_14")
    return (rsi is not None and rsi >= RSI_OVERBOUGHT_LINE
            and mfi is not None and mfi >= MFI_OVERBOUGHT_LINE)


# REV-004: RSI>70 & 볼린저 상단 (B5)
def _rule_rev_004(s: Signal) -> bool:
    rsi = _get(s.signals, "technical", "momentum", "rsi_14")
    bb_pos = _get(s.signals, "technical", "volatility", "bollinger_position")
    return (rsi is not None and rsi >= RSI_OVERBOUGHT_LINE
            and bb_pos == "upper_breakout")


# REV-005: MFI>80 & 볼린저 상단 (B6)
def _rule_rev_005(s: Signal) -> bool:
    mfi = _get(s.signals, "technical", "volume", "mfi_14")
    bb_pos = _get(s.signals, "technical", "volatility", "bollinger_position")
    return (mfi is not None and mfi >= MFI_OVERBOUGHT_LINE
            and bb_pos == "upper_breakout")


# QUAL-001: ROE 상위 20% 풀 ∩ RSI<30 (B35 — Fama-French Quality at low price)
# roe_rank_pct 0=최상위 → ≤ 0.20 이면 상위 20%.
def _rule_qual_001(s: Signal) -> bool:
    rsi = _get(s.signals, "technical", "momentum", "rsi_14")
    roe_rank = _get(s.signals, "fundamental", "profitability", "roe_rank_pct")
    return (rsi is not None and rsi <= RSI_OVERSOLD_LINE
            and roe_rank is not None and roe_rank <= ROE_TOP_PERCENTILE)


# EVT-001: 호재공시 & RSI>70 (B28 — 모멘텀 가속, 역설적 가설)
# recent_disclosures 에 impact_level=positive 가 하나라도 있고 RSI 가 과매수 상태.
# LOOKBACK_DAYS=2 라 최근 2일 윈도우 — 백테스트 window_days=2 와 일치.
def _rule_evt_001(s: Signal) -> bool:
    rsi = _get(s.signals, "technical", "momentum", "rsi_14")
    if rsi is None or rsi < RSI_OVERBOUGHT_LINE:
        return False
    discls = _get(s.signals, "event", "recent_disclosures") or []
    return any(d.get("impact_level") == "positive" for d in discls)


# TPL-001: RSI>70 & MFI>80 & 거래량 급증 (C2)
def _rule_tpl_001(s: Signal) -> bool:
    rsi = _get(s.signals, "technical", "momentum", "rsi_14")
    mfi = _get(s.signals, "technical", "volume", "mfi_14")
    vol_spike = _get(s.signals, "technical", "snapshot", "volume_spike")
    return (rsi is not None and rsi >= RSI_OVERBOUGHT_LINE
            and mfi is not None and mfi >= MFI_OVERBOUGHT_LINE
            and bool(vol_spike))


# TPL-002: RSI<30 & 볼린저 하단 & 거래량 급증 (C3 — 패닉 바닥)
def _rule_tpl_002(s: Signal) -> bool:
    rsi = _get(s.signals, "technical", "momentum", "rsi_14")
    bb_pos = _get(s.signals, "technical", "volatility", "bollinger_position")
    vol_spike = _get(s.signals, "technical", "snapshot", "volume_spike")
    return (rsi is not None and rsi <= RSI_OVERSOLD_LINE
            and bb_pos == "lower_breakout"
            and bool(vol_spike))


# TPL-003: RSI>70 & 볼린저 상단 & 거래량 급증 (C4)
def _rule_tpl_003(s: Signal) -> bool:
    rsi = _get(s.signals, "technical", "momentum", "rsi_14")
    bb_pos = _get(s.signals, "technical", "volatility", "bollinger_position")
    vol_spike = _get(s.signals, "technical", "snapshot", "volume_spike")
    return (rsi is not None and rsi >= RSI_OVERBOUGHT_LINE
            and bb_pos == "upper_breakout"
            and bool(vol_spike))


# TPL-004: RSI>70 & MFI>80 & 볼린저 상단 (C6 — 거래량 없는 트리플 과매수)
def _rule_tpl_004(s: Signal) -> bool:
    rsi = _get(s.signals, "technical", "momentum", "rsi_14")
    mfi = _get(s.signals, "technical", "volume", "mfi_14")
    bb_pos = _get(s.signals, "technical", "volatility", "bollinger_position")
    return (rsi is not None and rsi >= RSI_OVERBOUGHT_LINE
            and mfi is not None and mfi >= MFI_OVERBOUGHT_LINE
            and bb_pos == "upper_breakout")


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
    # 조합 트리거 — 백테스트 채택 11개.
    "REV-001":   _rule_rev_001,
    "REV-002":   _rule_rev_002,
    "REV-003":   _rule_rev_003,
    "REV-004":   _rule_rev_004,
    "REV-005":   _rule_rev_005,
    "QUAL-001":  _rule_qual_001,
    "EVT-001":   _rule_evt_001,
    "TPL-001":   _rule_tpl_001,
    "TPL-002":   _rule_tpl_002,
    "TPL-003":   _rule_tpl_003,
    "TPL-004":   _rule_tpl_004,
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
    # 조합 트리거 — 백테스트 채택 11개.
    "REV-001":   "이중 과매도 (RSI+MFI)",
    "REV-002":   "RSI 과매도 + 볼린저 하단",
    "REV-003":   "이중 과매수 (RSI+MFI)",
    "REV-004":   "RSI 과매수 + 볼린저 상단",
    "REV-005":   "MFI 과매수 + 볼린저 상단",
    "QUAL-001":  "퀄리티 종목 저가 매수 (ROE 상위 + RSI 과매도)",
    "EVT-001":   "호재공시 + RSI 과매수 (모멘텀 가속)",
    "TPL-001":   "트리플 과매수 패닉 (RSI+MFI+거래량)",
    "TPL-002":   "트리플 과매도 패닉 (RSI+볼린저+거래량)",
    "TPL-003":   "트리플 과매수 가속 (RSI+볼린저+거래량)",
    "TPL-004":   "트리플 과매수 (RSI+MFI+볼린저)",
}


# rule_id → 뉴스 요약 윈도우. ("days"|"hours", value) 튜플.
# event_publisher._summarize_news 가 트리거 시점 - window 범위의 기사를 LLM 요약.
#
# 시계열 트리거(RSI/MFI/MACD/BB/REV/QUAL): 일 단위 — 지표 룩백과 정렬 (RSI=14d, MACD=26d 등).
# 단기 이벤트(DART/SENT/PRICE/VOL/ATR/EVT/TPL): 시간 단위 — 시장 즉시 반응 윈도우.
#
# 값은 휴리스틱 디폴트 — 운영 백테스트(scripts/backfill/validate_news_window.py)로 튜닝.
RULE_NEWS_WINDOWS: dict[str, tuple[str, int]] = {
    # 시계열 — 일 단위
    "RSI-001":   ("days", 7),
    "RSI-002":   ("days", 7),
    "RSI-003":   ("days", 7),
    "RSI-004":   ("days", 7),
    "MACD-001":  ("days", 14),
    "MACD-002":  ("days", 14),
    "BB-001":    ("days", 14),
    "BB-002":    ("days", 14),
    "MFI-001":   ("days", 7),
    "MFI-002":   ("days", 7),
    # 단기 — 시간 단위
    "ATR-001":   ("hours", 12),
    "PRICE-001": ("hours", 6),
    "PRICE-002": ("hours", 6),
    "VOL-001":   ("hours", 6),
    "SENT-001":  ("hours", 12),
    "SENT-002":  ("hours", 12),
    "DART-001":  ("hours", 24),
    # 조합 — REV/QUAL 은 시계열성, EVT/TPL 은 단기성.
    "REV-001":   ("days", 14),
    "REV-002":   ("days", 14),
    "REV-003":   ("days", 14),
    "REV-004":   ("days", 14),
    "REV-005":   ("days", 14),
    "QUAL-001":  ("days", 14),
    "EVT-001":   ("hours", 24),
    "TPL-001":   ("hours", 24),
    "TPL-002":   ("hours", 24),
    "TPL-003":   ("hours", 24),
    "TPL-004":   ("hours", 24),
}


# RULES ↔ RULE_REASONS ↔ RULE_NEWS_WINDOWS 키셋 드리프트 차단 — 룰 추가 시 누락하면 모듈 로드 자체가 실패.
# event_publisher._build_payload 의 .get(rid, rid) fallback 은 production -O 모드 등 만일의 안전망.
_RULE_KEY_MISMATCH = (set(RULES) ^ set(RULE_REASONS)) | (set(RULES) ^ set(RULE_NEWS_WINDOWS))
if _RULE_KEY_MISMATCH:
    raise RuntimeError(
        f"RULES / RULE_REASONS / RULE_NEWS_WINDOWS 키셋 불일치: {sorted(_RULE_KEY_MISMATCH)}. "
        "세 dict 를 동시에 갱신해야 합니다."
    )


def pick_news_window(rule_ids: list[str]) -> tuple[str, int]:
    """동시 발화 룰들의 윈도우 중 가장 큰 값 선택.

    여러 룰이 같이 발화하면 더 긴 컨텍스트가 짧은 컨텍스트를 포함 — agent 가 직접
    최근/과거를 구분 가능. days/hours 단위가 섞이면 hours 로 환산 비교.

    Returns: ("days", n) 또는 ("hours", m). rule_ids 가 비면 ("hours", 24) fallback.
    """
    if not rule_ids:
        return ("hours", 24)

    def to_hours(kind: str, value: int) -> int:
        return value * 24 if kind == "days" else value

    candidates = [RULE_NEWS_WINDOWS[rid] for rid in rule_ids if rid in RULE_NEWS_WINDOWS]
    if not candidates:
        return ("hours", 24)
    return max(candidates, key=lambda kv: to_hours(*kv))


def window_to_timedelta(window: tuple[str, int]) -> timedelta:
    """("days"|"hours", value) → timedelta. MongoDB 시간 범위 쿼리용."""
    kind, value = window
    if kind == "days":
        return timedelta(days=value)
    return timedelta(hours=value)


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
