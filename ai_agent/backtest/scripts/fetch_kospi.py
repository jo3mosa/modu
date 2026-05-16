"""KOSPI/KOSDAQ 지수 일별 종가를 로컬 CSV로 저장 (yfinance 기반).

KRX가 2025년 이후 로그인 정책을 강화해 pykrx 접근이 막혔다.
yfinance는 야후 파이낸스 데이터로 무인증/무비용.

사용:
    python -m ai_agent.backtest.scripts.fetch_kospi --start 2023-01-01 --end 2025-12-31

출력:
    ai_agent/backtest/data/kospi_daily.csv
        date,kospi_close,kosdaq_close
"""
from __future__ import annotations

import argparse
from pathlib import Path

_DEFAULT_OUTPUT = Path(__file__).resolve().parent.parent / "data" / "kospi_daily.csv"

# 야후 파이낸스 심볼
_KOSPI_TICKER = "^KS11"
_KOSDAQ_TICKER = "^KQ11"


def main() -> None:
    parser = argparse.ArgumentParser(description="yfinance로 KOSPI/KOSDAQ 일별 종가 fetch")
    parser.add_argument("--start", required=True, help="YYYY-MM-DD")
    parser.add_argument("--end", required=True, help="YYYY-MM-DD (exclusive)")
    parser.add_argument("--output", type=Path, default=_DEFAULT_OUTPUT,
                        help=f"기본: {_DEFAULT_OUTPUT}")
    args = parser.parse_args()

    try:
        import yfinance as yf
    except ImportError:
        raise SystemExit("yfinance 미설치. 설치: pip install yfinance")
    import pandas as pd

    print(f"KOSPI ({_KOSPI_TICKER}) fetch: {args.start} ~ {args.end}")
    kospi = yf.download(_KOSPI_TICKER, start=args.start, end=args.end,
                        interval="1d", progress=False, auto_adjust=False)
    if kospi.empty:
        raise SystemExit(f"KOSPI 데이터 비어있음 — 기간 또는 ticker 확인")
    print(f"  → {len(kospi)} 거래일")

    print(f"KOSDAQ ({_KOSDAQ_TICKER}) fetch")
    kosdaq = yf.download(_KOSDAQ_TICKER, start=args.start, end=args.end,
                         interval="1d", progress=False, auto_adjust=False)
    print(f"  → {len(kosdaq)} 거래일")

    # yfinance는 MultiIndex 컬럼 ('Close', '^KS11') 형태 — 단일 ticker는 droplevel
    kospi_close = kospi["Close"].squeeze() if hasattr(kospi["Close"], "squeeze") else kospi["Close"]
    kosdaq_close = kosdaq["Close"].squeeze() if hasattr(kosdaq["Close"], "squeeze") else kosdaq["Close"]

    df = pd.DataFrame({
        "date": kospi.index.strftime("%Y-%m-%d"),
        "kospi_close": kospi_close.astype(float).values,
        "kosdaq_close": kosdaq_close.reindex(kospi.index).astype(float).values,
    })

    args.output.parent.mkdir(parents=True, exist_ok=True)
    df.to_csv(args.output, index=False, encoding="utf-8")
    print(f"\n저장 완료: {args.output}")
    print(f"기간: {df['date'].iloc[0]} ~ {df['date'].iloc[-1]} ({len(df)} 거래일)")
    print("\n샘플:")
    print(df.head(3).to_string(index=False))
    print("...")
    print(df.tail(3).to_string(index=False))


if __name__ == "__main__":
    main()
