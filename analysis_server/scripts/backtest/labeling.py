"""labeling — 트리거 발생 시점에 대한 가격 반응 라벨링.

백테스트의 기본 building block.

입력: 이벤트(트리거) 발생 (stock_code, event_ts, is_market_event)
출력: 각 horizon(거래일) 별 수익률 — `ret_T1`, `ret_T5`, `ret_T20`

핵심 규칙 (leakage 방지):
    1. 진입가(P0) 결정
       - is_market_event=True (공시·뉴스) :
           · 장 시작 전 (KST 09:00 미만)        → 당일 시가
           · 그 외 (장중·장후)                  → **익일** 거래일 시가
       - is_market_event=False (일별 기술지표) :
           · T일 종가 발견 → T+1 시가에 진입 (실거래 가능 시점)

    2. 출구가(P_end) = 진입 거래일에서 horizon 만큼 뒤의 거래일 **종가**
       - horizon=1 → 진입 거래일 종가 (= T+1 close, 일중 변동 + overnight gap 포함)
       - horizon=5 → 진입일 기준 5거래일 후 종가
       - 등

    3. 거래일 = pykrx 캘린더가 아니라 `daily_ohlcv` 에 실제 가격이 있는 일자.
       이렇게 하면 공휴일·임시 휴장·종목별 거래정지일 자동 반영.

성능:
    `label_events_batch(events_df)` — 종목별 OHLCV 패널을 한 번에 메모리 로드.
    이벤트 10만 건 기준 분 단위로 처리 가능. 단건 호출용 `label_event` 도 제공하지만
    매 호출마다 DB 쿼리 발생하므로 다건 처리는 batch 권장.

사용 예:
    from scripts.backtest.labeling import label_events_batch
    import pandas as pd

    events = pd.DataFrame({
        "stock_code": ["005930", "035420"],
        "event_ts":   pd.to_datetime(["2024-03-04 10:30", "2024-03-04 17:45"]).tz_localize("Asia/Seoul"),
        "is_market_event": [True, True],
    })
    labels = label_events_batch(events, horizons=(1, 5, 20))
"""

from __future__ import annotations

import logging
from datetime import date, datetime, time, timedelta
from functools import lru_cache
from typing import Iterable
from zoneinfo import ZoneInfo

import numpy as np
import pandas as pd
from sqlalchemy import text

from clients.postgres_client import get_engine

logger = logging.getLogger(__name__)

KST = ZoneInfo("Asia/Seoul")

# KRX 정규 거래시간 (KST). 시초가 결정은 09:00, 동시호가 종료 15:30.
# 09:00 이전 발생 이벤트만 "당일 시가 진입" 가능. 그 외는 익일 시가.
KRX_OPEN = time(9, 0)


def _to_kst_datetime(ts) -> datetime:
    """다양한 형태(pd.Timestamp / datetime / str) → tz-aware KST datetime."""
    if isinstance(ts, str):
        ts = pd.Timestamp(ts)
    if isinstance(ts, pd.Timestamp):
        ts = ts.to_pydatetime()
    if not isinstance(ts, datetime):
        raise TypeError(f"event_ts 변환 불가: {type(ts)}")
    if ts.tzinfo is None:
        # naive 면 KST 로 간주 (DART·뉴스 모두 KST 기준).
        ts = ts.replace(tzinfo=KST)
    else:
        ts = ts.astimezone(KST)
    return ts


def _resolve_anchor_date(event_ts: datetime, is_market_event: bool) -> date:
    """진입 거래일의 "후보" 기준일.

    - is_market_event=False (일별 기술지표): 이벤트 일자 = T → 진입 후보 = T+1 (달력일).
      실제 거래일 매핑은 caller 가 trading_days 리스트로 처리.
    - is_market_event=True (공시·뉴스):
        · 09:00 이전이면 당일이 후보 (당일 시가 진입 가능).
        · 그 외면 다음 달력일이 후보 (익일 시가 진입).
    """
    ev = _to_kst_datetime(event_ts)
    if is_market_event:
        if ev.time() < KRX_OPEN:
            return ev.date()
        return ev.date() + timedelta(days=1)
    # 기술지표: T일 종가에 발견 → T+1 후보.
    return ev.date() + timedelta(days=1)


def _next_trading_day(anchor: date, trading_days: np.ndarray) -> date | None:
    """anchor 이상인 첫 거래일. trading_days 는 정렬된 numpy datetime64[D] 배열."""
    anchor_np = np.datetime64(anchor, "D")
    idx = np.searchsorted(trading_days, anchor_np, side="left")
    if idx >= len(trading_days):
        return None
    return pd.Timestamp(trading_days[idx]).date()


def _shift_trading_days(d: date, steps: int, trading_days: np.ndarray) -> date | None:
    """거래일 d 에서 steps 거래일 만큼 이동한 거래일. 없으면 None."""
    d_np = np.datetime64(d, "D")
    idx = np.searchsorted(trading_days, d_np, side="left")
    if idx >= len(trading_days) or trading_days[idx] != d_np:
        # d 자체가 거래일이 아니면 호출 측 버그.
        return None
    target = idx + steps
    if target < 0 or target >= len(trading_days):
        return None
    return pd.Timestamp(trading_days[target]).date()


def _load_trading_days(start: date, end: date) -> np.ndarray:
    """[start, end] 구간의 거래일 — daily_ohlcv 에 실제 가격이 적재된 일자.

    종목 무관 — 어떤 종목이든 거래된 일자 = 거래일.
    """
    sql = text("""
        SELECT DISTINCT date
        FROM daily_ohlcv
        WHERE date >= :start AND date <= :end
        ORDER BY date
    """)
    with get_engine().connect() as conn:
        rows = conn.execute(sql, {"start": start, "end": end}).fetchall()
    if not rows:
        return np.array([], dtype="datetime64[D]")
    return np.array([r[0] for r in rows], dtype="datetime64[D]")


def _load_ohlcv_panel(stock_codes: Iterable[str],
                      start: date, end: date) -> pd.DataFrame:
    """종목 리스트의 OHLCV 를 한 번에 로드.

    반환: MultiIndex (stock_code, date) DataFrame with columns [open, high, low, close, volume].
    """
    stock_codes = list(set(stock_codes))
    if not stock_codes:
        return pd.DataFrame()
    sql = text("""
        SELECT stock_code, date, open, high, low, close, volume
        FROM daily_ohlcv
        WHERE stock_code = ANY(:codes)
          AND date >= :start AND date <= :end
        ORDER BY stock_code, date
    """)
    with get_engine().connect() as conn:
        df = pd.read_sql(
            sql, conn,
            params={"codes": stock_codes, "start": start, "end": end},
        )
    if df.empty:
        return df
    df["date"] = pd.to_datetime(df["date"]).dt.date
    df = df.set_index(["stock_code", "date"]).sort_index()
    return df


# ────────────────────────────────────────────────────────────────────
# 단건 라벨링 — 디버깅·소규모 호출용. 대량은 batch 사용.
# ────────────────────────────────────────────────────────────────────

def label_event(
    stock_code: str,
    event_ts: datetime,
    horizons: Iterable[int] = (1, 5, 20),
    *,
    is_market_event: bool = False,
) -> dict:
    """단일 이벤트 라벨링. 매 호출마다 DB 쿼리 — 대량 호출엔 batch 사용.

    반환 예시:
        {
            "stock_code": "005930",
            "event_ts": datetime(...),
            "entry_date": date(2024, 3, 5),
            "p_start": 73200.0,
            "ret_T1": 0.012, "ret_T5": -0.005, "ret_T20": 0.034,
            "exit_date_T1": date(2024, 3, 5), ...
        }

    어떤 horizon 이라도 가격을 못 구하면 그 값만 None — 다른 horizon 은 살림.
    """
    horizons = tuple(horizons)
    ev = _to_kst_datetime(event_ts)
    anchor = _resolve_anchor_date(ev, is_market_event)

    # 거래일·가격 윈도우 = anchor 부터 max(horizons)+10 거래일 여유.
    win_end = anchor + timedelta(days=max(horizons) * 2 + 30)
    trading_days = _load_trading_days(anchor, win_end)
    entry_date = _next_trading_day(anchor, trading_days)

    out: dict = {
        "stock_code": stock_code,
        "event_ts": ev,
        "anchor_date": anchor,
        "entry_date": entry_date,
        "p_start": None,
    }
    for h in horizons:
        out[f"ret_T{h}"] = None
        out[f"exit_date_T{h}"] = None

    if entry_date is None:
        return out

    panel = _load_ohlcv_panel([stock_code], entry_date, win_end)
    if panel.empty:
        return out

    try:
        p_start = float(panel.loc[(stock_code, entry_date), "open"])
    except KeyError:
        return out
    # 0/음수 진입가는 거래정지·데이터 오류 — 라벨링 불가.
    if p_start <= 0:
        return out
    out["p_start"] = p_start

    for h in horizons:
        exit_date = _shift_trading_days(entry_date, h, trading_days)
        if exit_date is None:
            continue
        try:
            p_end = float(panel.loc[(stock_code, exit_date), "close"])
        except KeyError:
            continue
        if p_end <= 0:
            continue
        out[f"ret_T{h}"] = p_end / p_start - 1.0
        out[f"exit_date_T{h}"] = exit_date

    return out


# ────────────────────────────────────────────────────────────────────
# 배치 라벨링 — 대량 이벤트 처리. 가격 패널 1회 로드.
# ────────────────────────────────────────────────────────────────────

def label_events_batch(
    events: pd.DataFrame,
    horizons: Iterable[int] = (1, 5, 20),
) -> pd.DataFrame:
    """이벤트 다발 → 라벨링 결과 DataFrame.

    `events` 컬럼 (필수):
        - stock_code : str
        - event_ts   : datetime-like (tz 없으면 KST 로 간주)
        - is_market_event : bool

    반환 컬럼:
        events 원본 + entry_date / p_start / ret_T{h} / exit_date_T{h}
    """
    horizons = tuple(horizons)
    if events.empty:
        return events.copy()

    required = {"stock_code", "event_ts", "is_market_event"}
    missing = required - set(events.columns)
    if missing:
        raise ValueError(f"events 에 누락된 컬럼: {missing}")

    df = events.copy().reset_index(drop=True)
    df["event_ts_kst"] = df["event_ts"].apply(_to_kst_datetime)
    df["anchor_date"] = [
        _resolve_anchor_date(ts, ime)
        for ts, ime in zip(df["event_ts_kst"], df["is_market_event"])
    ]

    # 거래일·가격 윈도우 한 번에 로드.
    min_anchor = df["anchor_date"].min()
    max_anchor = df["anchor_date"].max()
    win_end = max_anchor + timedelta(days=max(horizons) * 2 + 30)
    trading_days = _load_trading_days(min_anchor, win_end)

    panel = _load_ohlcv_panel(df["stock_code"].unique(), min_anchor, win_end)

    # 진입 거래일 매핑.
    df["entry_date"] = df["anchor_date"].apply(
        lambda d: _next_trading_day(d, trading_days)
    )

    # 가격 lookup — panel 의 MultiIndex 활용.
    # 0/음수 가격은 거래정지·데이터 오류 가능성이 높아 None 으로 처리
    # (None → 해당 horizon 라벨이 NaN 으로 빠지면서 자연스럽게 통계에서 제외).
    def _lookup(stock_code: str, d: date | None, col: str) -> float | None:
        if d is None:
            return None
        try:
            v = float(panel.loc[(stock_code, d), col])
        except KeyError:
            return None
        if col in ("open", "high", "low", "close") and v <= 0:
            return None
        return v

    df["p_start"] = [
        _lookup(sc, ed, "open")
        for sc, ed in zip(df["stock_code"], df["entry_date"])
    ]

    for h in horizons:
        exit_col = f"exit_date_T{h}"
        ret_col = f"ret_T{h}"
        df[exit_col] = df["entry_date"].apply(
            lambda d: _shift_trading_days(d, h, trading_days) if d is not None else None
        )
        # p_start, p_end 모두 양수일 때만 ret 계산. 그 외엔 None (NaN).
        rets = []
        for sc, ed, ps in zip(df["stock_code"], df[exit_col], df["p_start"]):
            if ps is None or ps <= 0 or ed is None:
                rets.append(None)
                continue
            pe = _lookup(sc, ed, "close")
            if pe is None or pe <= 0:
                rets.append(None)
                continue
            rets.append(pe / ps - 1.0)
        df[ret_col] = rets

    # 임시 컬럼 정리.
    df = df.drop(columns=["event_ts_kst"])
    return df


# ────────────────────────────────────────────────────────────────────
# 진단 — 라벨링 결과 요약.
# ────────────────────────────────────────────────────────────────────

def summarize_labels(labels: pd.DataFrame,
                     horizons: Iterable[int] = (1, 5, 20)) -> pd.DataFrame:
    """라벨링 결과의 horizon 별 통계 — mean / std / win_rate / n.

    트리거 가설 평가의 1차 표. 다중검정 보정·bootstrap CI 는 별도 단계에서.
    """
    rows = []
    for h in horizons:
        col = f"ret_T{h}"
        if col not in labels.columns:
            continue
        s = labels[col].dropna()
        if s.empty:
            rows.append({"horizon": h, "n": 0, "mean": None,
                         "std": None, "win_rate": None, "t_stat": None})
            continue
        mean = float(s.mean())
        std = float(s.std(ddof=1)) if len(s) > 1 else None
        # 1-표본 t 검정 (귀무가설: 평균 수익률 = 0).
        t_stat = (mean / (std / np.sqrt(len(s)))) if std and std > 0 else None
        rows.append({
            "horizon": h,
            "n": int(len(s)),
            "mean": mean,
            "std": std,
            "win_rate": float((s > 0).mean()),
            "t_stat": t_stat,
        })
    return pd.DataFrame(rows)


# ────────────────────────────────────────────────────────────────────
# 스모크 테스트 — `python -m scripts.backtest.labeling` 으로 단독 실행 가능.
# ────────────────────────────────────────────────────────────────────

def _smoke_test():
    """소규모 가짜 이벤트로 라벨링이 동작하는지 빠르게 확인."""
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    )

    # 23-24년 거래일 추출 (DB 적재되어 있다는 전제).
    days = _load_trading_days(date(2024, 1, 1), date(2024, 1, 31))
    if len(days) == 0:
        logger.error("거래일 0건 — daily_ohlcv 가 비어 있을 가능성")
        return
    logger.info("거래일 표본: %s ~ %s (%d일)", days[0], days[-1], len(days))

    # 삼성전자 + 네이버, 다양한 시각 조합.
    events = pd.DataFrame({
        "stock_code": ["005930", "005930", "035420", "035420"],
        "event_ts": pd.to_datetime([
            "2024-01-03 08:30",  # 장 전 → 당일 시가 진입
            "2024-01-03 10:30",  # 장중   → 익일 시가 진입
            "2024-01-04 17:45",  # 장후   → 익일 시가 진입
            "2024-01-05 21:00",  # 야간   → 다음 거래일 시가 진입
        ]).tz_localize("Asia/Seoul"),
        "is_market_event": [True, True, True, True],
    })

    labels = label_events_batch(events, horizons=(1, 5, 20))
    print("\n=== 라벨링 결과 ===")
    print(labels.to_string(index=False))

    print("\n=== horizon 별 요약 ===")
    print(summarize_labels(labels).to_string(index=False))


if __name__ == "__main__":
    _smoke_test()
