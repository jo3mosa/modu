"""scripts/backtest/event_generator.py

가설 ID → 트리거 이벤트 DataFrame 생성 dispatcher.

각 가설은 SignalExtractor 의 신호 함수들을 단일/조합(intersect_same_day,
intersect_window, filter_by_pool) 로 평가해 (stock_code, event_ts,
is_market_event, hypothesis_id) 이벤트를 만든다.

이 결과를 labeling.label_events_batch 에 그대로 넣으면 가설별 라벨링 완료.

사용:
    DB_HOST=localhost python -m scripts.backtest.event_generator \\
        --start 2023-01-01 --end 2023-12-31 --output events_2023.parquet
"""

from __future__ import annotations

import argparse
import logging
import time
from pathlib import Path

import pandas as pd

from scripts.backtest.hypotheses import ALL_HYPOTHESES, HYPOTHESES_BY_ID
from scripts.backtest.signals import (
    SignalExtractor,
    filter_by_pool,
    intersect_same_day,
    intersect_window,
)

logger = logging.getLogger(__name__)

# 공시·뉴스 ↔ 기술 신호 조합의 시간 윈도우 (달력일).
# 공시 발생 후 2일 이내에 확인 신호가 와야 "연관 사건" 으로 본다.
DEFAULT_WINDOW_DAYS = 2


# ────────────────────────────────────────────────────────────────────
# 가설별 빌더 — 각각 SignalExtractor 를 받아 이벤트 DataFrame 반환
# ────────────────────────────────────────────────────────────────────

def _build_registry() -> dict:
    """가설 ID → 빌더 함수.

    동일한 ext 인스턴스를 모든 호출에 넘기면 panel 데이터(indicators / ohlcv /
    news_panel / fundamentals)는 첫 호출에서만 DB hit, 이후 캐시 재사용.
    """
    R: dict = {}

    # ── A 단독 (19개) — 단순 함수 호출 ───────────────────────────
    R["A1"]  = lambda ext: ext.rsi_oversold()
    R["A2"]  = lambda ext: ext.rsi_overbought()
    R["A3"]  = lambda ext: ext.mfi_oversold()
    R["A4"]  = lambda ext: ext.mfi_overbought()
    R["A5"]  = lambda ext: ext.macd_golden()
    R["A6"]  = lambda ext: ext.macd_dead()
    R["A7"]  = lambda ext: ext.sma20_breakout_up()
    R["A8"]  = lambda ext: ext.sma20_breakout_down()
    R["A9"]  = lambda ext: ext.bollinger_lower_touch()
    R["A10"] = lambda ext: ext.bollinger_upper_touch()
    R["A11"] = lambda ext: ext.bb_width_surge()
    R["A12"] = lambda ext: ext.atr_surge()
    R["A13"] = lambda ext: ext.volume_surge()
    R["A14"] = lambda ext: ext.disclosure_positive()
    R["A15"] = lambda ext: ext.disclosure_negative()
    R["A16"] = lambda ext: ext.disclosure_urgent()
    R["A17"] = lambda ext: ext.news_strong_positive()
    R["A18"] = lambda ext: ext.news_strong_negative()
    R["A19"] = lambda ext: ext.news_sentiment_jump()

    # ── B 2지표 조합 (26개) ────────────────────────────────────────

    # 이중 mean-reversion (B1-B6)
    R["B1"] = lambda ext: intersect_same_day(ext.rsi_oversold(),    ext.mfi_oversold())
    R["B2"] = lambda ext: intersect_same_day(ext.rsi_oversold(),    ext.bollinger_lower_touch())
    R["B3"] = lambda ext: intersect_same_day(ext.mfi_oversold(),    ext.bollinger_lower_touch())
    R["B4"] = lambda ext: intersect_same_day(ext.rsi_overbought(),  ext.mfi_overbought())
    R["B5"] = lambda ext: intersect_same_day(ext.rsi_overbought(),  ext.bollinger_upper_touch())
    R["B6"] = lambda ext: intersect_same_day(ext.mfi_overbought(),  ext.bollinger_upper_touch())

    # Momentum transition mirror (B7-B8)
    R["B7"] = lambda ext: intersect_same_day(ext.macd_golden(),     ext.sma20_breakout_up())
    R["B8"] = lambda ext: intersect_same_day(ext.macd_dead(),       ext.sma20_breakout_down())

    # 이벤트 × 거래량 (B18-B19) — 공시 후 window 내 거래량 급증
    R["B18"] = lambda ext: intersect_window(ext.disclosure_positive(), ext.volume_surge(),
                                            window_days=DEFAULT_WINDOW_DAYS)
    R["B19"] = lambda ext: intersect_window(ext.disclosure_negative(), ext.volume_surge(),
                                            window_days=DEFAULT_WINDOW_DAYS)

    # 이벤트 × 미디어 (B21-B22) — 공시 ± 미디어 sentiment 동조
    R["B21"] = lambda ext: intersect_window(ext.disclosure_positive(), ext.news_strong_positive(),
                                            window_days=DEFAULT_WINDOW_DAYS)
    R["B22"] = lambda ext: intersect_window(ext.disclosure_negative(), ext.news_strong_negative(),
                                            window_days=DEFAULT_WINDOW_DAYS)

    # 이벤트 × 기술 (B23-B26) — 공시 후 window 내 기술 신호
    R["B23"] = lambda ext: intersect_window(ext.disclosure_positive(), ext.sma20_breakout_up(),
                                            window_days=DEFAULT_WINDOW_DAYS)
    R["B24"] = lambda ext: intersect_window(ext.disclosure_negative(), ext.sma20_breakout_down(),
                                            window_days=DEFAULT_WINDOW_DAYS)
    R["B25"] = lambda ext: intersect_window(ext.disclosure_positive(), ext.macd_golden(),
                                            window_days=DEFAULT_WINDOW_DAYS)
    R["B26"] = lambda ext: intersect_window(ext.disclosure_negative(), ext.macd_dead(),
                                            window_days=DEFAULT_WINDOW_DAYS)

    # 역설적 — 공시 시점에 이미 RSI 극단 (B27-B28)
    # 같은 날에 두 조건 동시 충족.
    R["B27"] = lambda ext: intersect_same_day(ext.disclosure_negative(), ext.rsi_oversold())
    R["B28"] = lambda ext: intersect_same_day(ext.disclosure_positive(), ext.rsi_overbought())

    # 미디어 × 기술 (B29-B32) — 뉴스 강양/음 후 기술 신호
    R["B29"] = lambda ext: intersect_window(ext.news_strong_positive(), ext.sma20_breakout_up(),
                                            window_days=DEFAULT_WINDOW_DAYS)
    R["B30"] = lambda ext: intersect_window(ext.news_strong_negative(), ext.sma20_breakout_down(),
                                            window_days=DEFAULT_WINDOW_DAYS)
    R["B31"] = lambda ext: intersect_window(ext.news_strong_positive(), ext.macd_golden(),
                                            window_days=DEFAULT_WINDOW_DAYS)
    R["B32"] = lambda ext: intersect_window(ext.news_strong_negative(), ext.macd_dead(),
                                            window_days=DEFAULT_WINDOW_DAYS)

    # 미디어 × 거래량 (B33-B34)
    R["B33"] = lambda ext: intersect_window(ext.news_strong_positive(), ext.volume_surge(),
                                            window_days=DEFAULT_WINDOW_DAYS)
    R["B34"] = lambda ext: intersect_window(ext.news_strong_negative(), ext.volume_surge(),
                                            window_days=DEFAULT_WINDOW_DAYS)

    # Filter × Transition — 학계 표준 (B35, B38)
    R["B35"] = lambda ext: filter_by_pool(ext.rsi_oversold(),  ext.roe_top_membership())
    R["B38"] = lambda ext: filter_by_pool(ext.macd_golden(),   ext.pbr_bottom_membership())

    # ── C 트리플 (10개) — 2지표 조합 결과를 다시 third 신호와 교집합 ───

    # Mean-reversion + 거래량 (C1-C2)
    R["C1"] = lambda ext: intersect_same_day(
        intersect_same_day(ext.rsi_oversold(),   ext.mfi_oversold()),
        ext.volume_surge(),
    )
    R["C2"] = lambda ext: intersect_same_day(
        intersect_same_day(ext.rsi_overbought(), ext.mfi_overbought()),
        ext.volume_surge(),
    )
    # 단일 reversion + 볼린저 + 거래량 (C3-C4)
    R["C3"] = lambda ext: intersect_same_day(
        intersect_same_day(ext.rsi_oversold(),   ext.bollinger_lower_touch()),
        ext.volume_surge(),
    )
    R["C4"] = lambda ext: intersect_same_day(
        intersect_same_day(ext.rsi_overbought(), ext.bollinger_upper_touch()),
        ext.volume_surge(),
    )
    # 트리플 과매수/매도 (거래량 X) — RSI & MFI & 볼린저 (C5-C6)
    R["C5"] = lambda ext: intersect_same_day(
        intersect_same_day(ext.rsi_oversold(),   ext.mfi_oversold()),
        ext.bollinger_lower_touch(),
    )
    R["C6"] = lambda ext: intersect_same_day(
        intersect_same_day(ext.rsi_overbought(), ext.mfi_overbought()),
        ext.bollinger_upper_touch(),
    )
    # Momentum 트리플 mirror (C7-C8)
    R["C7"] = lambda ext: intersect_same_day(
        intersect_same_day(ext.macd_golden(),    ext.sma20_breakout_up()),
        ext.volume_surge(),
    )
    R["C8"] = lambda ext: intersect_same_day(
        intersect_same_day(ext.macd_dead(),      ext.sma20_breakout_down()),
        ext.volume_surge(),
    )
    # 이벤트 3중 확인 (C9-C10) — 공시 + 거래량 + 미디어 sentiment
    R["C9"]  = lambda ext: intersect_window(
        intersect_window(ext.disclosure_positive(), ext.volume_surge(),
                         window_days=DEFAULT_WINDOW_DAYS),
        ext.news_strong_positive(),
        window_days=DEFAULT_WINDOW_DAYS,
    )
    R["C10"] = lambda ext: intersect_window(
        intersect_window(ext.disclosure_negative(), ext.volume_surge(),
                         window_days=DEFAULT_WINDOW_DAYS),
        ext.news_strong_negative(),
        window_days=DEFAULT_WINDOW_DAYS,
    )

    return R


HYPOTHESIS_REGISTRY = _build_registry()


# 정합성 검증 — hypotheses.py 에 정의된 ID 와 registry 가 일치하는지.
_defined_ids = {h.id for h in ALL_HYPOTHESES}
_registry_ids = set(HYPOTHESIS_REGISTRY.keys())
_missing = _defined_ids - _registry_ids
_extra = _registry_ids - _defined_ids
if _missing:
    raise RuntimeError(
        f"event_generator registry 누락 가설 ID: {sorted(_missing)}"
    )
if _extra:
    raise RuntimeError(
        f"event_generator registry 잉여 ID (hypotheses.py 에 없음): {sorted(_extra)}"
    )


# ────────────────────────────────────────────────────────────────────
# 실행
# ────────────────────────────────────────────────────────────────────

def generate_for(hypothesis_id: str, ext: SignalExtractor) -> pd.DataFrame:
    """단일 가설 이벤트 생성. hypothesis_id 컬럼 부착."""
    fn = HYPOTHESIS_REGISTRY[hypothesis_id]
    df = fn(ext)
    if df is None or df.empty:
        return pd.DataFrame(columns=["hypothesis_id", "stock_code",
                                      "event_ts", "is_market_event"])
    out = df.copy()
    out["hypothesis_id"] = hypothesis_id
    return out[["hypothesis_id", "stock_code", "event_ts", "is_market_event"]]


def generate_all(start_date: str, end_date: str,
                 hypothesis_ids: list[str] | None = None) -> pd.DataFrame:
    """모든 (또는 선택된) 가설의 이벤트를 합쳐 반환.

    Args:
        hypothesis_ids: None 이면 hypotheses.py 의 전체 55개.
    """
    ext = SignalExtractor(start_date, end_date)
    targets = hypothesis_ids or [h.id for h in ALL_HYPOTHESES]

    parts: list[pd.DataFrame] = []
    summary: list[dict] = []
    for hid in targets:
        t0 = time.monotonic()
        try:
            df = generate_for(hid, ext)
        except Exception:
            logger.exception("[%s] 생성 실패 — skip", hid)
            df = pd.DataFrame(columns=["hypothesis_id", "stock_code",
                                        "event_ts", "is_market_event"])
        elapsed = time.monotonic() - t0
        n_events = len(df)
        n_stocks = df["stock_code"].nunique() if n_events else 0
        h = HYPOTHESES_BY_ID.get(hid)
        h_name = h.name if h else "?"
        summary.append({
            "id": hid, "name": h_name,
            "n_events": n_events, "n_stocks": n_stocks,
            "elapsed_s": round(elapsed, 1),
        })
        logger.info("[%s] %-30s n=%6d stocks=%5d (%.1fs)",
                    hid, h_name[:30], n_events, n_stocks, elapsed)
        parts.append(df)

    all_events = pd.concat(parts, ignore_index=True)
    logger.info("총 %d 이벤트 / %d 가설", len(all_events), len(targets))

    # 요약 표 함께 출력 (stderr 가 아닌 log 로)
    summary_df = pd.DataFrame(summary)
    logger.info("\n%s", summary_df.to_string(index=False))

    return all_events


def main():
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )
    parser = argparse.ArgumentParser()
    parser.add_argument("--start", required=True, help="YYYY-MM-DD")
    parser.add_argument("--end",   required=True, help="YYYY-MM-DD")
    parser.add_argument("--output", default=None,
                        help="parquet 출력 경로 (기본: events_<start_year>.parquet)")
    parser.add_argument("--only", default=None,
                        help="처리할 가설 ID 쉼표 구분 (디버깅용)")
    args = parser.parse_args()

    only = [x.strip() for x in args.only.split(",")] if args.only else None
    df = generate_all(args.start, args.end, hypothesis_ids=only)

    out_path = args.output or f"events_{args.start[:4]}.parquet"
    out_path = Path(out_path)
    df.to_parquet(out_path)
    logger.info("저장 완료: %s (%d rows)", out_path, len(df))


if __name__ == "__main__":
    main()
