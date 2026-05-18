"""scripts/backtest/signals.py

각 신호(트리거 구성 요소)의 발생 시점을 추출하는 함수 모음.

설계:
    SignalExtractor 클래스를 생성하면 panel 데이터를 lazy load 후 캐시.
    같은 인스턴스에서 여러 신호 함수를 호출하면 DB hit 1회로 모두 처리.

모든 신호 함수는 통일된 형식 반환:
    pd.DataFrame[stock_code: str, event_ts: tz-aware KST datetime, is_market_event: bool]

event_ts 규칙:
    - 기술 transition (RSI<30 진입 등)  → date 00:00 KST. 라벨링 시 T+1 시가 진입
                                          (is_market_event=False)
    - 이벤트 (공시·뉴스)                → 실제 발표 시각. is_market_event=True

Transition 정의:
    "어제는 임계 위, 오늘 임계 아래" 같은 첫 진입 시점만 발화.
    며칠 연속 RSI<30 이어도 첫 진입일에만 트리거. (자연스러운 cooldown)

카테고리:
    [기술] RSI/MFI overbought·oversold, MACD cross, SMA20 breakout,
           볼린저 터치·폭 급증, ATR 급증, SMA 정배열 진입 (멤버십)
    [거래량] volume surge (20일 평균 대비 N배)
    [공시] impact_level positive/negative + URGENT 키워드
    [뉴스] sentiment 강양/강음 진입, sentiment 급변
    [펀더멘털 필터(멤버십)] ROE 상위 풀, PBR 하위 풀, SMA 정배열 풀
"""

from __future__ import annotations

import os
from datetime import date, datetime, time, timedelta
from functools import cached_property
from typing import Optional
from zoneinfo import ZoneInfo

import numpy as np
import pandas as pd
from dotenv import load_dotenv
from sqlalchemy import text

from clients.postgres_client import get_engine

# .env 의 MONGO_URI 로드 (analysis_server/ 기준 상위 2단계).
_ENV_PATH = os.path.join(os.path.dirname(__file__), "../../../.env")
load_dotenv(dotenv_path=_ENV_PATH)

KST = ZoneInfo("Asia/Seoul")

# 워밍업 — LAG/rolling 계산을 위해 start 이전 데이터를 함께 로드.
# 60일이면 SMA60, 20일 거래량 평균 등 대부분 지표가 정상 계산됨.
DEFAULT_WARMUP_DAYS = 60


# ────────────────────────────────────────────────────────────────────
# 헬퍼
# ────────────────────────────────────────────────────────────────────

def _to_date(d) -> date:
    if isinstance(d, str):
        return datetime.strptime(d, "%Y-%m-%d").date()
    if isinstance(d, datetime):
        return d.date()
    return d


def _date_to_kst_ts(d) -> datetime:
    """date → KST 00:00 datetime."""
    if isinstance(d, pd.Timestamp):
        d = d.date()
    return datetime(d.year, d.month, d.day, 0, 0, tzinfo=KST)


def _empty_event_frame() -> pd.DataFrame:
    """일관된 빈 결과 — 다운스트림에서 concat 안전."""
    return pd.DataFrame({"stock_code": pd.Series(dtype=str),
                         "event_ts":  pd.Series(dtype="datetime64[ns, Asia/Seoul]")})


# ────────────────────────────────────────────────────────────────────
# SignalExtractor
# ────────────────────────────────────────────────────────────────────

class SignalExtractor:
    """주어진 구간 [start_date, end_date] 에 대한 신호 추출기.

    같은 인스턴스에서 여러 신호 함수를 호출 시 panel 데이터는 재사용된다.
    """

    def __init__(self, start_date: str, end_date: str,
                 *, warmup_days: int = DEFAULT_WARMUP_DAYS):
        self.start_date = _to_date(start_date)
        self.end_date = _to_date(end_date)
        self.warmup_start = self.start_date - timedelta(days=warmup_days)

    # ── 데이터 panel (lazy load + cache) ───────────────────────────

    @cached_property
    def indicators(self) -> pd.DataFrame:
        """daily_indicators panel — 기술 transition 산출용.

        반환 컬럼: stock_code, date, rsi_14, mfi_14, macd_state, bollinger_position,
                   sma_alignment, sma_20, bb_upper, bb_lower, atr, close
        """
        sql = text("""
            SELECT i.stock_code, i.date,
                   i.rsi_14, i.mfi_14,
                   i.macd_state, i.bollinger_position, i.sma_alignment,
                   i.sma_20, i.bb_upper, i.bb_lower, i.atr,
                   o.close
            FROM daily_indicators i
            JOIN daily_ohlcv o
              ON o.stock_code = i.stock_code AND o.date = i.date
            WHERE i.date >= :warmup AND i.date <= :end_d
            ORDER BY i.stock_code, i.date
        """)
        with get_engine().connect() as conn:
            df = pd.read_sql(sql, conn, params={
                "warmup": self.warmup_start, "end_d": self.end_date,
            })
        df["date"] = pd.to_datetime(df["date"]).dt.date
        # 종목별 정렬 — groupby + shift 가 시간순으로 동작하도록.
        df = df.sort_values(["stock_code", "date"]).reset_index(drop=True)
        return df

    @cached_property
    def ohlcv(self) -> pd.DataFrame:
        """daily_ohlcv panel — 거래량 급증 산출용 (close 는 indicators 에 있음)."""
        sql = text("""
            SELECT stock_code, date, volume
            FROM daily_ohlcv
            WHERE date >= :warmup AND date <= :end_d
            ORDER BY stock_code, date
        """)
        with get_engine().connect() as conn:
            df = pd.read_sql(sql, conn, params={
                "warmup": self.warmup_start, "end_d": self.end_date,
            })
        df["date"] = pd.to_datetime(df["date"]).dt.date
        df = df.sort_values(["stock_code", "date"]).reset_index(drop=True)
        return df

    # ── 공통 transition 헬퍼 ────────────────────────────────────────

    def _mask_to_events(self, df: pd.DataFrame, mask: pd.Series) -> pd.DataFrame:
        """boolean mask 가 True 인 (stock_code, date) → event DataFrame.
        start_date 이전 (워밍업 구간) 은 제외.
        기술 신호 → is_market_event=False (라벨링이 T+1 시가 진입 적용).
        """
        in_range = pd.Series(df["date"]) >= self.start_date
        full_mask = mask & in_range
        if not full_mask.any():
            out = _empty_event_frame()
            out["is_market_event"] = pd.Series(dtype=bool)
            return out
        out = df.loc[full_mask, ["stock_code", "date"]].copy()
        out["event_ts"] = out["date"].apply(_date_to_kst_ts)
        out["is_market_event"] = False
        return out[["stock_code", "event_ts", "is_market_event"]].reset_index(drop=True)

    def _transition_cross_threshold(
        self, value_col: str, threshold: float, direction: str,
    ) -> pd.DataFrame:
        """`어제 ≥ threshold → 오늘 < threshold` (direction='below')
           또는 `어제 ≤ threshold → 오늘 > threshold` (direction='above') transition."""
        df = self.indicators
        prev = df.groupby("stock_code")[value_col].shift(1)
        if direction == "below":
            mask = (prev >= threshold) & (df[value_col] < threshold)
        elif direction == "above":
            mask = (prev <= threshold) & (df[value_col] > threshold)
        else:
            raise ValueError(f"direction={direction!r}")
        return self._mask_to_events(df, mask)

    # ── 기술 transition 신호 ────────────────────────────────────────
    # A1: RSI<30 진입 / A2: RSI>70 진입

    def rsi_oversold(self, threshold: float = 30.0) -> pd.DataFrame:
        return self._transition_cross_threshold("rsi_14", threshold, "below")

    def rsi_overbought(self, threshold: float = 70.0) -> pd.DataFrame:
        return self._transition_cross_threshold("rsi_14", threshold, "above")

    # A3: MFI<20 / A4: MFI>80

    def mfi_oversold(self, threshold: float = 20.0) -> pd.DataFrame:
        return self._transition_cross_threshold("mfi_14", threshold, "below")

    def mfi_overbought(self, threshold: float = 80.0) -> pd.DataFrame:
        return self._transition_cross_threshold("mfi_14", threshold, "above")

    # A5: MACD 골든크로스 / A6: MACD 데드크로스
    # historical_indicator_loader 에서 macd_state 가 이미 라벨링되어 있음 — 바로 사용.

    def macd_golden(self) -> pd.DataFrame:
        df = self.indicators
        mask = df["macd_state"] == "bullish_cross"
        return self._mask_to_events(df, mask)

    def macd_dead(self) -> pd.DataFrame:
        df = self.indicators
        mask = df["macd_state"] == "bearish_cross"
        return self._mask_to_events(df, mask)

    # A7: SMA20 상향 돌파 / A8: SMA20 하향 돌파
    # close 가 sma_20 을 어제는 아래(위), 오늘은 위(아래) 인 케이스.

    def sma20_breakout_up(self) -> pd.DataFrame:
        df = self.indicators
        rel = df["close"] - df["sma_20"]
        prev_rel = rel.groupby(df["stock_code"]).shift(1)
        mask = (prev_rel <= 0) & (rel > 0)
        return self._mask_to_events(df, mask)

    def sma20_breakout_down(self) -> pd.DataFrame:
        df = self.indicators
        rel = df["close"] - df["sma_20"]
        prev_rel = rel.groupby(df["stock_code"]).shift(1)
        mask = (prev_rel >= 0) & (rel < 0)
        return self._mask_to_events(df, mask)

    # A9: 볼린저 하단 터치 / A10: 볼린저 상단 터치
    # bollinger_position 라벨 transition: inside_band → lower_breakout / upper_breakout 진입.

    def bollinger_lower_touch(self) -> pd.DataFrame:
        df = self.indicators
        prev = df.groupby("stock_code")["bollinger_position"].shift(1)
        mask = (prev != "lower_breakout") & (df["bollinger_position"] == "lower_breakout")
        return self._mask_to_events(df, mask)

    def bollinger_upper_touch(self) -> pd.DataFrame:
        df = self.indicators
        prev = df.groupby("stock_code")["bollinger_position"].shift(1)
        mask = (prev != "upper_breakout") & (df["bollinger_position"] == "upper_breakout")
        return self._mask_to_events(df, mask)

    # A11: 볼린저 밴드폭 급증
    # bb_width = bb_upper - bb_lower. 20일 평균 대비 multiplier 배 이상 + 이전엔 평균 이하.

    def bb_width_surge(self, multiplier: float = 1.5,
                       window: int = 20) -> pd.DataFrame:
        df = self.indicators.copy()
        df["bb_width"] = df["bb_upper"] - df["bb_lower"]
        rolling_mean = (
            df.groupby("stock_code")["bb_width"]
              .transform(lambda s: s.rolling(window, min_periods=window).mean())
        )
        ratio = df["bb_width"] / rolling_mean
        prev_ratio = ratio.groupby(df["stock_code"]).shift(1)
        mask = (prev_ratio <= multiplier) & (ratio > multiplier)
        return self._mask_to_events(df, mask)

    # A12: ATR 급증 (변동성 확장 transition)

    def atr_surge(self, multiplier: float = 1.5,
                  window: int = 20) -> pd.DataFrame:
        df = self.indicators
        rolling_mean = (
            df.groupby("stock_code")["atr"]
              .transform(lambda s: s.rolling(window, min_periods=window).mean())
        )
        ratio = df["atr"] / rolling_mean
        prev_ratio = ratio.groupby(df["stock_code"]).shift(1)
        mask = (prev_ratio <= multiplier) & (ratio > multiplier)
        return self._mask_to_events(df, mask)

    # ── 거래량 ──────────────────────────────────────────────────────
    # A13: 거래량 급증 — 20일 평균 대비 multiplier 배 이상 진입.

    def volume_surge(self, multiplier: float = 2.0,
                     window: int = 20) -> pd.DataFrame:
        df = self.ohlcv
        rolling_mean = (
            df.groupby("stock_code")["volume"]
              .transform(lambda s: s.rolling(window, min_periods=window).mean())
        )
        ratio = df["volume"] / rolling_mean
        prev_ratio = ratio.groupby(df["stock_code"]).shift(1)
        mask = (prev_ratio <= multiplier) & (ratio > multiplier) & (rolling_mean > 0)
        return self._mask_to_events(df, mask)

    # ── 공시 (MongoDB modu_mongo.disclosures) ─────────────────────────
    # event_ts = rcept_datetime 우선, 없으면 rcept_date (KST 자정).
    # 라벨링이 시각 따라 당일/익일 시가 진입 자동 처리.

    @cached_property
    def _mongo_disclosures(self):
        from pymongo import MongoClient
        return MongoClient(os.environ["MONGO_URI"]).modu_mongo.disclosures

    @property
    def _yyyymmdd_range(self) -> dict:
        return {
            "$gte": self.start_date.strftime("%Y%m%d"),
            "$lte": self.end_date.strftime("%Y%m%d"),
        }

    def _disclosure_events_query(self, base_query: dict) -> pd.DataFrame:
        """공시 컬렉션 쿼리 → (stock_code, event_ts, is_market_event=True) DataFrame."""
        query = {
            "stock_code": {"$ne": None},
            "rcept_dt":   self._yyyymmdd_range,
            **base_query,
        }
        cursor = self._mongo_disclosures.find(
            query,
            projection={"stock_code": 1, "rcept_datetime": 1,
                        "rcept_date": 1, "_id": 0},
        )
        rows = []
        for d in cursor:
            sc = d.get("stock_code")
            if not sc:
                continue
            # 보강된 시각 우선. 없으면 rcept_date (자정).
            ts = d.get("rcept_datetime") or d.get("rcept_date")
            if ts is None:
                continue
            # MongoDB 시간은 보통 UTC offset 포함된 datetime — KST 로 변환.
            if isinstance(ts, datetime):
                ts = ts.astimezone(KST) if ts.tzinfo else ts.replace(tzinfo=KST)
            rows.append({"stock_code": sc.zfill(6), "event_ts": ts})

        if not rows:
            out = _empty_event_frame()
            out["is_market_event"] = pd.Series(dtype=bool)
            return out
        df = pd.DataFrame(rows)
        df["is_market_event"] = True
        return df.reset_index(drop=True)

    # A14: 호재공시 발생
    def disclosure_positive(self) -> pd.DataFrame:
        return self._disclosure_events_query({"impact_level": "positive"})

    # A15: 악재공시 발생
    def disclosure_negative(self) -> pd.DataFrame:
        return self._disclosure_events_query({"impact_level": "negative"})

    # A16: URGENT 공시 발생 — report_nm 키워드 매칭
    # URGENT_KEYWORDS 는 collectors.disclosure_collector 의 정의와 일치.
    URGENT_KEYWORDS = ("조회공시", "거래정지", "관리종목", "감사의견거절",
                       "소송", "회생절차", "상장폐지", "영업정지", "배임")

    def disclosure_urgent(self) -> pd.DataFrame:
        pattern = "|".join(self.URGENT_KEYWORDS)
        return self._disclosure_events_query({"report_nm": {"$regex": pattern}})

    # ── 뉴스 sentiment (MongoDB modu_mongo.news_articles) ──────────
    # 뉴스 1건 → N개 종목 매칭. unwind 후 종목별 일별 평균 sentiment.
    # event_ts = 일자 KST 00:00 — 라벨링은 T+1 시가 진입 (보수적).
    # 뉴스 published_at 분포는 KST 새벽~심야 다양. 매번 다른 진입가는 노이즈 큼.

    # 매칭 false positive 가지치기 — stock_match_meta 기반.
    @staticmethod
    def _is_strong_news_match(meta: dict) -> bool:
        methods = meta.get("methods", [])
        if "code" in methods:
            return True                              # 코드 직접 언급
        if meta.get("in_title") and meta.get("hits", 0) >= 2:
            return True                              # 제목 + 2회 이상
        name = meta.get("name", "") or ""
        if meta.get("hits", 0) >= 3 and len(name) >= 3:
            return True                              # 본문 3회 + 종목명 3자+
        return False

    @cached_property
    def news_panel(self) -> pd.DataFrame:
        """일별 종목별 sentiment 집계 panel.

        반환: stock_code, date, avg_sentiment, n_news (모두 stock_codes 매핑된 23-24).
        Strong filter (_is_strong_news_match) 통과한 매칭만 사용 — false positive 가지치기.
        워밍업: 지표와 동일 (transition LAG 위해 warmup_start 부터).
        """
        from pymongo import MongoClient
        coll = MongoClient(os.environ["MONGO_URI"]).modu_mongo.news_articles

        # date 는 YYYYMMDD 문자열.
        warmup_yyyymmdd = self.warmup_start.strftime("%Y%m%d")
        end_yyyymmdd = self.end_date.strftime("%Y%m%d")

        cursor = coll.find(
            {
                "date": {"$gte": warmup_yyyymmdd, "$lte": end_yyyymmdd},
                "sentiment_score": {"$exists": True},
                "stock_match_meta": {"$exists": True, "$not": {"$size": 0}},
            },
            projection={"date": 1, "sentiment_score": 1,
                        "stock_match_meta": 1, "_id": 0},
        )

        rows = []
        for doc in cursor:
            score = doc.get("sentiment_score")
            yyyymmdd = doc.get("date") or ""
            if score is None or len(yyyymmdd) != 8:
                continue
            try:
                d = datetime.strptime(yyyymmdd, "%Y%m%d").date()
            except ValueError:
                continue
            for m in doc.get("stock_match_meta", []):
                if not self._is_strong_news_match(m):
                    continue
                code = (m.get("code") or "").zfill(6)
                if not code:
                    continue
                rows.append({"stock_code": code, "date": d,
                             "sentiment_score": float(score)})

        if not rows:
            return pd.DataFrame(columns=["stock_code", "date",
                                          "avg_sentiment", "n_news"])

        df = pd.DataFrame(rows)
        daily = (
            df.groupby(["stock_code", "date"], as_index=False)
              .agg(avg_sentiment=("sentiment_score", "mean"),
                   n_news=("sentiment_score", "size"))
              .sort_values(["stock_code", "date"])
              .reset_index(drop=True)
        )
        return daily

    def _news_transition_to_events(self, mask: pd.Series,
                                   df: pd.DataFrame) -> pd.DataFrame:
        in_range = pd.Series(df["date"]) >= self.start_date
        full = mask & in_range
        if not full.any():
            out = _empty_event_frame()
            out["is_market_event"] = pd.Series(dtype=bool)
            return out
        out = df.loc[full, ["stock_code", "date"]].copy()
        out["event_ts"] = out["date"].apply(_date_to_kst_ts)
        out["is_market_event"] = True
        return out[["stock_code", "event_ts", "is_market_event"]].reset_index(drop=True)

    # A17: 뉴스감성 강양 진입
    def news_strong_positive(self, threshold: float = 30.0) -> pd.DataFrame:
        df = self.news_panel.copy()
        if df.empty:
            return self._news_transition_to_events(pd.Series([], dtype=bool), df)
        df["prev"] = df.groupby("stock_code")["avg_sentiment"].shift(1).fillna(0.0)
        mask = (df["prev"] <= threshold) & (df["avg_sentiment"] > threshold)
        return self._news_transition_to_events(mask, df)

    # A18: 뉴스감성 강음 진입
    def news_strong_negative(self, threshold: float = 30.0) -> pd.DataFrame:
        df = self.news_panel.copy()
        if df.empty:
            return self._news_transition_to_events(pd.Series([], dtype=bool), df)
        # -threshold 미만 진입.
        neg = -threshold
        df["prev"] = df.groupby("stock_code")["avg_sentiment"].shift(1).fillna(0.0)
        mask = (df["prev"] >= neg) & (df["avg_sentiment"] < neg)
        return self._news_transition_to_events(mask, df)

    # A19: 뉴스감성 급변 — |Δsentiment| > threshold
    def news_sentiment_jump(self, threshold: float = 30.0) -> pd.DataFrame:
        df = self.news_panel.copy()
        if df.empty:
            return self._news_transition_to_events(pd.Series([], dtype=bool), df)
        df["prev"] = df.groupby("stock_code")["avg_sentiment"].shift(1).fillna(0.0)
        mask = (df["avg_sentiment"] - df["prev"]).abs() > threshold
        return self._news_transition_to_events(mask, df)

    # ── 펀더멘털 / SMA 정배열 (멤버십 필터 — 트리거 X) ────────────────
    # 멤버십은 (stock_code, date) 풀로 표현. 다른 신호와 교집합 시 stock+date 매칭.

    @cached_property
    def fundamentals(self) -> pd.DataFrame:
        """daily_fundamentals panel — PER/PBR/ROE 등 일별 종목 펀더멘털."""
        sql = text("""
            SELECT stock_code, date, per, pbr, roe
            FROM daily_fundamentals
            WHERE date >= :start_d AND date <= :end_d
            ORDER BY date, stock_code
        """)
        with get_engine().connect() as conn:
            df = pd.read_sql(sql, conn, params={
                "start_d": self.start_date, "end_d": self.end_date,
            })
        df["date"] = pd.to_datetime(df["date"]).dt.date
        return df

    def _percentile_membership(self, col: str, percentile: float,
                               descending: bool) -> pd.DataFrame:
        """일별 cross-sectional percentile 멤버십 → (stock_code, date).

        descending=True 면 col 상위 N%, False 면 하위 N%.
        """
        df = self.fundamentals.dropna(subset=[col]).copy()
        if df.empty:
            return pd.DataFrame(columns=["stock_code", "date"])
        # 종목 분위는 일별 cross-section — pandas rank pct.
        if descending:
            df["rank_pct"] = df.groupby("date")[col].rank(pct=True, ascending=False)
        else:
            df["rank_pct"] = df.groupby("date")[col].rank(pct=True, ascending=True)
        threshold = percentile / 100.0
        return df.loc[df["rank_pct"] <= threshold, ["stock_code", "date"]].reset_index(drop=True)

    def roe_top_membership(self, percentile: float = 20.0) -> pd.DataFrame:
        """ROE 상위 percentile% 풀. B35/C13 같은 Quality 필터용."""
        return self._percentile_membership("roe", percentile, descending=True)

    def pbr_bottom_membership(self, percentile: float = 20.0) -> pd.DataFrame:
        """PBR 하위 percentile% 풀. B38/C14 같은 Value 필터용."""
        return self._percentile_membership("pbr", percentile, descending=False)

    def sma_aligned_membership(self) -> pd.DataFrame:
        """SMA 정배열 (bullish_aligned) 풀. B39/C12 같은 추세 필터용."""
        df = self.indicators
        in_range = pd.Series(df["date"]) >= self.start_date
        mask = (df["sma_alignment"] == "bullish_aligned") & in_range
        if not mask.any():
            return pd.DataFrame(columns=["stock_code", "date"])
        return df.loc[mask, ["stock_code", "date"]].reset_index(drop=True)


# ────────────────────────────────────────────────────────────────────
# 조합 헬퍼 — 가설별 이벤트 생성에서 사용
# ────────────────────────────────────────────────────────────────────

def _ensure_event_cols(df: pd.DataFrame) -> pd.DataFrame:
    """이벤트 DataFrame 표준 컬럼 보장 — 빈 입력도 안전 처리."""
    if df is None or df.empty:
        out = _empty_event_frame()
        out["is_market_event"] = pd.Series(dtype=bool)
        return out
    cols = ["stock_code", "event_ts"]
    if "is_market_event" not in df.columns:
        df = df.copy()
        df["is_market_event"] = False
    return df[cols + ["is_market_event"]]


def intersect_same_day(df_a: pd.DataFrame, df_b: pd.DataFrame) -> pd.DataFrame:
    """같은 (stock_code, KST date) 에서 두 신호 모두 발화.

    event_ts 는 그 날 KST 00:00 으로 통일.
    is_market_event 는 둘 중 하나라도 True 면 True (이벤트성 우선).

    예) B1 (RSI<30 & MFI<20) = intersect_same_day(rsi_oversold, mfi_oversold)
    """
    a = _ensure_event_cols(df_a).copy()
    b = _ensure_event_cols(df_b).copy()
    if a.empty or b.empty:
        return _ensure_event_cols(None)

    a["date"] = pd.to_datetime(a["event_ts"], utc=True).dt.tz_convert(KST).dt.date
    b["date"] = pd.to_datetime(b["event_ts"], utc=True).dt.tz_convert(KST).dt.date

    merged = a.merge(
        b[["stock_code", "date", "is_market_event"]],
        on=["stock_code", "date"], suffixes=("_a", "_b"),
    )
    if merged.empty:
        return _ensure_event_cols(None)

    out = merged[["stock_code", "date"]].drop_duplicates().copy()
    out["event_ts"] = out["date"].apply(_date_to_kst_ts)
    is_me_a = merged.groupby(["stock_code", "date"])["is_market_event_a"].any().reset_index()
    is_me_b = merged.groupby(["stock_code", "date"])["is_market_event_b"].any().reset_index()
    is_me = is_me_a.merge(is_me_b, on=["stock_code", "date"])
    is_me["is_market_event"] = is_me["is_market_event_a"] | is_me["is_market_event_b"]
    out = out.merge(is_me[["stock_code", "date", "is_market_event"]],
                    on=["stock_code", "date"], how="left")
    return out[["stock_code", "event_ts", "is_market_event"]].reset_index(drop=True)


def intersect_window(df_event: pd.DataFrame, df_confirm: pd.DataFrame,
                     window_days: int = 2) -> pd.DataFrame:
    """df_event 발생 후 window_days 달력일 내에 같은 종목에서 df_confirm 발생.

    트리거 시점 = df_confirm 의 event_ts (확인 신호 시점이 "사건의 완성").
    is_market_event = df_confirm 의 값.

    한 (stock, confirm_ts) 가 여러 event 와 매칭돼도 중복 제거.

    예) B18 (호재공시 & 거래량 급증)
        = intersect_window(disclosure_positive, volume_surge, window_days=2)
        → 호재공시 발생 후 2일 내 거래량 급증한 (종목, 거래량급증일) 만 채택.
    """
    e = _ensure_event_cols(df_event).copy()
    c = _ensure_event_cols(df_confirm).copy()
    if e.empty or c.empty:
        return _ensure_event_cols(None)

    e["event_ts"] = pd.to_datetime(e["event_ts"], utc=True).dt.tz_convert(KST)
    c["event_ts"] = pd.to_datetime(c["event_ts"], utc=True).dt.tz_convert(KST)

    # 종목 기준 cross join — 메모리 폭주 방지를 위해 종목별 처리.
    merged = e.rename(columns={"event_ts": "ts_e"}).merge(
        c.rename(columns={"event_ts": "ts_c", "is_market_event": "is_me_c"}),
        on="stock_code",
    )
    if merged.empty:
        return _ensure_event_cols(None)

    delta_days = (merged["ts_c"] - merged["ts_e"]).dt.total_seconds() / 86400.0
    mask = (delta_days >= 0) & (delta_days <= window_days)
    matched = merged[mask]
    if matched.empty:
        return _ensure_event_cols(None)

    out = (
        matched[["stock_code", "ts_c", "is_me_c"]]
        .rename(columns={"ts_c": "event_ts", "is_me_c": "is_market_event"})
        .drop_duplicates(subset=["stock_code", "event_ts"])
        .reset_index(drop=True)
    )
    return out[["stock_code", "event_ts", "is_market_event"]]


def filter_by_pool(df_event: pd.DataFrame, pool: pd.DataFrame) -> pd.DataFrame:
    """event 의 (stock_code, KST date) 가 pool 멤버에 속하는 것만 유지.

    pool: stock_code, date 컬럼만 있는 DataFrame (멤버십 lookup).

    예) B35 (ROE 상위 풀 ∩ RSI<30)
        = filter_by_pool(rsi_oversold, roe_top_membership)
    """
    e = _ensure_event_cols(df_event).copy()
    if e.empty or pool is None or pool.empty:
        return _ensure_event_cols(None)

    e["date"] = pd.to_datetime(e["event_ts"], utc=True).dt.tz_convert(KST).dt.date

    keep = e.merge(
        pool[["stock_code", "date"]].drop_duplicates(),
        on=["stock_code", "date"], how="inner",
    )
    if keep.empty:
        return _ensure_event_cols(None)
    return keep[["stock_code", "event_ts", "is_market_event"]].drop_duplicates().reset_index(drop=True)


# ────────────────────────────────────────────────────────────────────
# 스모크 테스트
# ────────────────────────────────────────────────────────────────────

def _smoke():
    """한 달 추출 — 모든 신호 카테고리 동작 검증.

    실행:
        DB_HOST=localhost python -m scripts.backtest.signals
    """
    import logging
    logging.basicConfig(level=logging.INFO, format="%(message)s")

    ext = SignalExtractor("2024-01-01", "2024-01-31")

    print(f"\n구간: {ext.start_date} ~ {ext.end_date}")
    print(f"{'가설':<30} {'발화 수':>10}  {'고유 종목':>10}")
    print("-" * 60)

    sections = [
        ("[기술 transition]", [
            ("A1 RSI<30",            ext.rsi_oversold()),
            ("A2 RSI>70",            ext.rsi_overbought()),
            ("A3 MFI<20",            ext.mfi_oversold()),
            ("A4 MFI>80",            ext.mfi_overbought()),
            ("A5 MACD 골든",          ext.macd_golden()),
            ("A6 MACD 데드",          ext.macd_dead()),
            ("A7 SMA20 상향",         ext.sma20_breakout_up()),
            ("A8 SMA20 하향",         ext.sma20_breakout_down()),
            ("A9 볼린저 하단 터치",    ext.bollinger_lower_touch()),
            ("A10 볼린저 상단 터치",   ext.bollinger_upper_touch()),
            ("A11 볼린저 폭 급증",     ext.bb_width_surge()),
            ("A12 ATR 급증",          ext.atr_surge()),
            ("A13 거래량 급증",        ext.volume_surge()),
        ]),
        ("[공시]", [
            ("A14 호재공시",          ext.disclosure_positive()),
            ("A15 악재공시",          ext.disclosure_negative()),
            ("A16 URGENT 공시",       ext.disclosure_urgent()),
        ]),
        ("[뉴스 sentiment]", [
            ("A17 강양 진입",         ext.news_strong_positive()),
            ("A18 강음 진입",         ext.news_strong_negative()),
            ("A19 sentiment 급변",   ext.news_sentiment_jump()),
        ]),
    ]
    for section_name, samples in sections:
        print(f"\n{section_name}")
        for name, df in samples:
            n_events = len(df)
            n_stocks = df["stock_code"].nunique() if n_events else 0
            print(f"  {name:<28} {n_events:>10,}  {n_stocks:>10,}")

    print("\n[멤버십 필터 (트리거 X — pool 크기)]")
    roe_pool = ext.roe_top_membership(percentile=20.0)
    pbr_pool = ext.pbr_bottom_membership(percentile=20.0)
    sma_pool = ext.sma_aligned_membership()
    print(f"  ROE 상위 20% pool             {len(roe_pool):>10,} (stock·date pair, "
          f"{roe_pool['stock_code'].nunique() if len(roe_pool) else 0:,} 종목)")
    print(f"  PBR 하위 20% pool             {len(pbr_pool):>10,} (stock·date pair, "
          f"{pbr_pool['stock_code'].nunique() if len(pbr_pool) else 0:,} 종목)")
    print(f"  SMA 정배열 pool                {len(sma_pool):>10,} (stock·date pair, "
          f"{sma_pool['stock_code'].nunique() if len(sma_pool) else 0:,} 종목)")


if __name__ == "__main__":
    _smoke()
