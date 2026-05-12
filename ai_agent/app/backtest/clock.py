"""SimulatedClock.

시작일~종료일 사이 거래일을 한 스텝씩 진행한다.
주말은 자동으로 건너뛴다. 한국 공휴일은 별도 캘린더 주입이 필요하나,
MVP에서는 주말 스킵만 처리하고 OHLCV 미존재일은 signal_replay 단에서 자연스럽게 0개 신호로 처리된다.
"""
from datetime import date, datetime, time, timedelta


class SimulatedClock:
    def __init__(self, start: date, end: date, market_open_kst: time = time(9, 0)) -> None:
        if start > end:
            raise ValueError(f"start({start})가 end({end})보다 미래입니다.")
        self._start = start
        self._end = end
        self._market_open = market_open_kst
        self._current = self._advance_to_weekday(start)

    @property
    def current_date(self) -> date:
        return self._current

    @property
    def current_as_of(self) -> datetime:
        """retrieval/memory_log에 주입할 datetime. 시뮬 시점의 장 시작 시각으로 통일."""
        return datetime.combine(self._current, self._market_open)

    def is_done(self) -> bool:
        return self._current > self._end

    def tick(self) -> None:
        """다음 거래일로 진행. 주말은 건너뜀."""
        self._current = self._advance_to_weekday(self._current + timedelta(days=1))

    @staticmethod
    def _advance_to_weekday(d: date) -> date:
        while d.weekday() >= 5:
            d += timedelta(days=1)
        return d
