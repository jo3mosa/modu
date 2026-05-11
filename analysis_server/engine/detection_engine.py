"""detection_engine

Analysis Signal 기반 Detection Rule 충족 여부 판단 → 충족 rule_ids 목록 생성.
RSI/MACD/BB/SMA/ATR/MFI/PRICE/VOL/SENT/DART 계열 룰을 평가한다.
"""


def detect(signal) -> list[str]:
    raise NotImplementedError("detection_engine.detect is not implemented yet")
