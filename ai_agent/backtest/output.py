"""백테스트 결과 기록 — 일자별 JSONL + 종합 summary JSON.

설계 의도:
  - 트리거·의사결정·체결을 한 줄(JSONL) 로 묶어 후처리 자유도 확보 (pandas
    read_json(lines=True) 또는 jq 로 즉시 분석).
  - 일자별로 파일 분리 — 큰 백테스트도 streaming I/O 로 메모리 부담 없음.
  - 실행 메타(run_id, started_at, config) 는 별도 summary 파일 — 재현성 추적.

AI 팀이 자체 메트릭(수익률·MDD·Sharpe) 모듈을 붙일 때 이 JSONL 을 입력으로 쓰면 됨.
체결 결과(Fill) 도 같은 줄에 포함되므로 행 단위 PnL 계산이 자연스럽다.
"""

from __future__ import annotations

import json
import logging
from contextlib import contextmanager
from dataclasses import asdict, is_dataclass
from datetime import date, datetime
from pathlib import Path
from typing import Any, Iterator, Optional

from .interfaces import Decision, Fill, Trigger

logger = logging.getLogger(__name__)


# ─── 직렬화 ──────────────────────────────────────────────────────────────────

def _serialize(obj: Any) -> Any:
    """json.dumps default — dataclass / date / datetime 자동 변환."""
    if is_dataclass(obj):
        return asdict(obj)
    if isinstance(obj, (date, datetime)):
        return obj.isoformat()
    if isinstance(obj, set):
        return sorted(obj)
    raise TypeError(f"non-serializable: {type(obj).__name__}")


def _to_jsonable(value: Any) -> Any:
    """dataclass·datetime 을 재귀적으로 dict/str 로 — json.dumps 가 default 콜백
    으로 잡지 못하는 nested 케이스(dataclass 안의 list[dataclass] 등) 회피."""
    if is_dataclass(value):
        return {k: _to_jsonable(v) for k, v in asdict(value).items()}
    if isinstance(value, dict):
        return {k: _to_jsonable(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [_to_jsonable(v) for v in value]
    if isinstance(value, (date, datetime)):
        return value.isoformat()
    return value


# ─── 라이터 ──────────────────────────────────────────────────────────────────

class JsonlWriter:
    """일자별 JSONL 파일에 트리거 + 의사결정 + 체결 결과를 줄 단위 append.

    파일 핸들은 day 가 바뀔 때만 재오픈 — 큰 백테스트에서도 fopen 비용 없음.
    """

    def __init__(self, root: Path):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self._current_day: Optional[date] = None
        self._fp = None

    def _path_for(self, day: date) -> Path:
        return self.root / f"triggers_{day.isoformat()}.jsonl"

    def _ensure_day(self, day: date) -> None:
        if self._current_day == day and self._fp is not None:
            return
        if self._fp is not None:
            self._fp.close()
        self._fp = open(self._path_for(day), "a", encoding="utf-8")
        self._current_day = day

    def write(self, day: date, record: dict) -> None:
        self._ensure_day(day)
        self._fp.write(json.dumps(_to_jsonable(record), ensure_ascii=False) + "\n")

    def flush(self) -> None:
        if self._fp is not None:
            self._fp.flush()

    def close(self) -> None:
        if self._fp is not None:
            self._fp.close()
            self._fp = None
            self._current_day = None


@contextmanager
def open_writer(root: Path) -> Iterator[JsonlWriter]:
    w = JsonlWriter(root)
    try:
        yield w
    finally:
        w.close()


# ─── 레코드 빌더 ─────────────────────────────────────────────────────────────

def build_record(
    *,
    run_id: str,
    user_id: str,
    trigger: Trigger,
    decision: Optional[Decision],
    fill: Optional[Fill],
    user_context: Optional[dict] = None,
    portfolio_snapshot: Optional[Any] = None,
    extras: Optional[dict] = None,
    mode: Optional[str] = None,
) -> dict:
    """한 트리거의 처리 결과를 직렬화 가능한 dict 로.

    decision=None 은 의사결정 단계 도달 못 한 케이스(예: 의사결정 함수 예외).
    fill=None 은 hold 결정이거나 체결 시뮬레이션 skip.
    mode: backtest CLI --mode 그대로. dashboard가 mode별 grouping에 사용. None이면
        dashboard가 "default"로 fallback (혼선 원인이라 가급적 전달 권장).
    """
    return {
        "run_id": run_id,
        "user_id": user_id,
        "mode": mode,
        "as_of_date": trigger.as_of_date,
        "stock_code": trigger.stock_code,
        "rule_ids": list(trigger.rule_ids),
        "rule_reasons": list(trigger.rule_reasons),
        "close_price": trigger.close_price,
        # signal payload 4종 — AI 팀 분석·디버깅용.
        "signals": {
            "technical":   trigger.technical,
            "fundamental": trigger.fundamental,
            "event":       trigger.event,
            "sentiment":   trigger.sentiment,
        },
        "decision":           decision,           # _to_jsonable 가 asdict 변환
        "fill":               fill,
        "user_context":       user_context,
        "portfolio_snapshot": _try_jsonable(portfolio_snapshot),
        "extras":             extras or {},
        "recorded_at":        datetime.utcnow().isoformat() + "Z",
    }


def _try_jsonable(value: Any) -> Any:
    """PortfolioFn.snapshot() 이 임의 객체를 반환할 수 있음 — JSON 직렬화 실패는
    fallback repr 로 처리(데이터 누락보다는 부정확한 기록이 디버깅에 유용)."""
    if value is None:
        return None
    try:
        return _to_jsonable(value)
    except Exception:
        return {"_repr": repr(value)}


# ─── 종합 summary ────────────────────────────────────────────────────────────

def write_summary(root: Path, *,
                  run_id: str,
                  started_at: datetime,
                  ended_at: datetime,
                  config_dict: dict,
                  stats: dict) -> Path:
    """run 종료 시 1회 호출. stats 에는 trigger 수·action 분포 등 누적치."""
    root = Path(root)
    root.mkdir(parents=True, exist_ok=True)
    path = root / f"summary_{run_id}.json"
    payload = {
        "run_id":     run_id,
        "started_at": started_at.isoformat() + "Z",
        "ended_at":   ended_at.isoformat() + "Z",
        "elapsed_sec": (ended_at - started_at).total_seconds(),
        "config":     config_dict,
        "stats":      stats,
    }
    path.write_text(
        json.dumps(_to_jsonable(payload), ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    return path
