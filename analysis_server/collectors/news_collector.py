"""news_collector

한국경제·연합인포맥스 RSS 비정기 폴링 → FinBERT 감성 분석 → Redis `sentiment:{stock_code}` 갱신.
독립 실행 수집 모듈.
"""


def run() -> None:
    raise NotImplementedError("news_collector.run is not implemented yet")


if __name__ == "__main__":
    run()
