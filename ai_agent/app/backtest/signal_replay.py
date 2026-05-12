"""Signal replay.

특정 시뮬레이션 시점에 발생했을 신호를 재생성한다.

설계 의도:
- SignalSource Protocol을 두어 detection_engine 실구현(DA팀) 후 한 줄 교체 가능.
- MVP에서는 MockSignalSource로 결정론적 신호 생성: 종목/날짜 해시 기반.
  실제 detection_engine.detect()가 구현되면 RealSignalSource로 swap.
"""
from datetime import date, datetime
from hashlib import md5
from typing import Protocol

from app.triggers.schemas import MarketTrigger, MarketTriggerEvent


class SignalSource(Protocol):
    """과거 시점 신호 생성기 인터페이스."""

    def signals_for(
        self,
        target_date: date,
        stock_codes: list[str],
    ) -> list[MarketTriggerEvent]: ...


class MockSignalSource:
    """결정론적 mock signal 생성기.

    종목코드 + 날짜 해시로 (signal 발생 여부, rule_id, snapshot 값)을 재현 가능하게 만든다.
    실제 detection_engine 구현 후 RealSignalSource로 교체.
    """

    _RULES = [
        ("RSI-002", "RSI 과매수"),
        ("RSI-001", "RSI 과매도"),
        ("MACD-001", "MACD 골든크로스"),
        ("VOL-001", "거래량 급증"),
        ("DART-004", "공시 이벤트"),
    ]

    def __init__(self, fire_probability: float = 0.15) -> None:
        if not 0 <= fire_probability <= 1:
            raise ValueError("fire_probability는 0~1 사이여야 합니다.")
        self._fire_p = fire_probability

    def signals_for(
        self,
        target_date: date,
        stock_codes: list[str],
    ) -> list[MarketTriggerEvent]:
        events: list[MarketTriggerEvent] = []
        for code in stock_codes:
            h = self._hash(code, target_date)
            if (h % 1000) / 1000.0 >= self._fire_p:
                continue
            rule_id, reason = self._RULES[h % len(self._RULES)]
            events.append(self._build_event(code, target_date, rule_id, reason, h))
        return events

    @staticmethod
    def _hash(code: str, d: date) -> int:
        digest = md5(f"{code}|{d.isoformat()}".encode()).hexdigest()
        return int(digest[:8], 16)

    @staticmethod
    def _build_event(
        code: str,
        d: date,
        rule_id: str,
        reason: str,
        h: int,
    ) -> MarketTriggerEvent:
        ts = datetime.combine(d, datetime.min.time()).replace(hour=9)
        snapshot = {
            "stock_code": code,
            "timestamp": ts.isoformat(),
            "signals": {
                "technical": {"rsi": 25 + (h % 50)},
                "fundamental": {},
                "event": {"has_urgent_issue": rule_id.startswith("DART")},
                "sentiment": {"daily_score": ((h % 200) - 100) / 100.0},
            },
        }
        return MarketTriggerEvent(
            stock_code=code,
            timestamp=ts,
            trigger=MarketTrigger(rule_ids=[rule_id], trigger_reason=[reason]),
            analysis_snapshot=snapshot,
        )
