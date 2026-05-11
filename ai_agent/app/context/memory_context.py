from typing import Any

from app.memory.interfaces import MemoryStore


def load_memory_context(
    user_id: int,
    stock_codes: list[str],
    sectors: list[str],
    key_signals: list[str],
    memory_store: MemoryStore,
    days: int = 30,
) -> dict[str, Any]:
    """MemoryStore에서 과거 유사 판단 이력을 조회해 memory_context를 구성한다."""
    if not stock_codes and not sectors and not key_signals:
        return {
            "query_basis": {
                "stock_codes": [],
                "sectors": [],
                "key_signals": [],
            },
            "recent_decisions": [],
            "recent_loss_decisions": [],
            "summary": "조회 기준이 없어 최근 판단 이력 조회를 생략했습니다.",
        }

    recent_decisions = memory_store.get_recent_decisions(
        user_id=user_id,
        stock_codes=stock_codes,
        sectors=sectors,
        key_signals=key_signals,
        limit=10,
        days=days,
    )

    # only_loss=True: 손실로 끝난 판단만 별도 조회해 Decision Manager가 반복 실수를 피하게 한다
    recent_loss_decisions = memory_store.get_similar_decisions(
        user_id=user_id,
        stock_codes=stock_codes,
        sectors=sectors,
        key_signals=key_signals,
        limit=5,
        days=days,
        only_loss=True,
    )

    return {
        "query_basis": {
            "stock_codes": stock_codes,
            "sectors": sectors,
            "key_signals": key_signals,
        },
        "recent_decisions": recent_decisions,
        "recent_loss_decisions": recent_loss_decisions,
        "summary": _summarize(recent_decisions, recent_loss_decisions),
    }


def extract_stock_codes(candidate_assets: list[dict[str, Any]]) -> list[str]:
    """후보 종목에서 stock_code(또는 ticker) 첫 등장 순서를 유지하며 중복 없이 추출한다."""
    return list(dict.fromkeys(
        asset.get("stock_code") or asset.get("ticker")
        for asset in candidate_assets
        if asset.get("stock_code") or asset.get("ticker")
    ))


def extract_sectors(candidate_assets: list[dict[str, Any]]) -> list[str]:
    """후보 종목에서 섹터를 첫 등장 순서를 유지하며 중복 없이 추출한다."""
    return list(dict.fromkeys(
        asset["sector"]
        for asset in candidate_assets
        if asset.get("sector")
    ))


def extract_key_signals(analysis_snapshot: dict[str, Any]) -> list[str]:
    """
    analysis_snapshot.signals에서 활성화된 신호 종류를 문자열 리스트로 반환한다.

    TODO: 신호 문자열 값은 Analysis Layer codebook enum으로 교체 필요
    """
    signals = analysis_snapshot.get("signals", {})
    key_signals: set[str] = set()

    if signals.get("technical"):
        key_signals.add("technical_signal")
    if signals.get("fundamental"):
        key_signals.add("fundamental_signal")
    if (signals.get("event") or {}).get("has_urgent_issue"):
        key_signals.add("event_signal")
    if (signals.get("sentiment") or {}).get("daily_score") is not None:
        key_signals.add("sentiment_signal")

    return sorted(key_signals)


def _summarize(
    recent_decisions: list[Any],
    recent_loss_decisions: list[Any],
) -> str:
    if not recent_decisions and not recent_loss_decisions:
        return "현재 후보와 직접적으로 유사한 최근 판단 이력은 확인되지 않았습니다."

    return (
        f"유사 판단 {len(recent_decisions)}건, "
        f"최근 손실 판단 {len(recent_loss_decisions)}건이 확인되었습니다. "
        "Decision Manager는 최근 손실 판단의 근거와 현재 신호의 중복 여부를 우선 확인해야 합니다."
    )
