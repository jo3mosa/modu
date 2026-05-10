from sqlalchemy.engine import Engine

from app.memory.interfaces import (
    DecisionLog,
    PastDecision,
    PostMortemRecord,
)
from app.memory.memory_log import MemoryLog
from app.memory.retrieval import DecisionRetrieval


class DBMemoryStore:
    """
    MemoryStore Protocol의 DB 구현체.

    DecisionRetrieval(조회)과 MemoryLog(저장)을 합성해 Protocol을 충족한다.
    """

    def __init__(self, engine: Engine) -> None:
        self._retrieval = DecisionRetrieval(engine)
        self._log = MemoryLog(engine)

    def get_recent_decisions(
        self,
        user_id: int,
        stock_codes: list[str],
        sectors: list[str],
        key_signals: list[str],
        limit: int = 10,
        days: int = 30,
    ) -> list[PastDecision]:
        return self._retrieval.get_recent_decisions(
            user_id=user_id,
            stock_codes=stock_codes,
            sectors=sectors,
            key_signals=key_signals,
            limit=limit,
            days=days,
        )

    def get_similar_decisions(
        self,
        user_id: int,
        stock_codes: list[str],
        sectors: list[str],
        key_signals: list[str],
        limit: int = 5,
        days: int = 30,
        only_loss: bool = False,
    ) -> list[PastDecision]:
        return self._retrieval.get_similar_decisions(
            user_id=user_id,
            stock_codes=stock_codes,
            sectors=sectors,
            key_signals=key_signals,
            limit=limit,
            days=days,
            only_loss=only_loss,
        )

    def store_decision(self, log: DecisionLog) -> int:
        return self._log.store_decision(log)

    def store_postmortem(self, report: PostMortemRecord) -> int:
        return self._log.store_postmortem(report)
