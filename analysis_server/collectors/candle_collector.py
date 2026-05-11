"""candle_collector

KIS API 1분봉 → 기술 지표(RSI/MACD/BB/SMA/ATR/MFI) 계산 → Redis `technical:{stock_code}` 갱신.
1분 주기로 독립 실행되는 수집 모듈.
"""


def run() -> None:
    raise NotImplementedError("candle_collector.run is not implemented yet")


if __name__ == "__main__":
    run()
