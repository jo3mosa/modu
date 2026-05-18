from collections import Counter
from datetime import datetime
from typing import Any

from app.memory.interfaces import MemoryStore, PastDecision

# _brief_table / _recent_post_mortems에서 자유 텍스트 길이 상한.
# LLM 컨텍스트 토큰을 통제하면서 핵심 의미는 보존하기 위한 절충 값.
_REASON_PREVIEW_CHARS = 120
_LOSS_TEXT_PREVIEW_CHARS = 80


def load_memory_context(
    user_id: int,
    stock_codes: list[str],
    sectors: list[str],
    key_signals: list[str],
    memory_store: MemoryStore,
    days: int = 30,
    as_of: datetime | None = None,
) -> dict[str, Any]:
    """MemoryStore에서 과거 유사 판단 이력을 조회해 memory_context를 구성한다.

    as_of: 조회 기준 시각. None이면 NOW(). backtest는 시뮬레이션 시점 주입.

    반환 dict는 두 묶음으로 나뉜다:
      - LLM 프롬프트 1급 노출용 정제 키 (lessons_aggregate / loss_pattern_brief /
        similar_decisions_table / recent_post_mortems) — 노드는 이 4개를 별도
        프롬프트 변수로 분리 주입해 LLM 주의를 집중시킨다.
      - 디버깅/트레이스용 원본 키 (query_basis / recent_decisions /
        recent_loss_decisions / summary) — 호환성 유지를 위해 그대로 둔다.
    """
    if not stock_codes and not sectors and not key_signals:
        return {
            "query_basis": {
                "stock_codes": [],
                "sectors": [],
                "key_signals": [],
            },
            "recent_decisions": [],
            "recent_loss_decisions": [],
            "lessons_aggregate": [],
            "loss_pattern_brief": "(조회 기준 없음 — 메모리 회상 생략)",
            "similar_decisions_table": [],
            "recent_post_mortems": [],
            "summary": "조회 기준이 없어 최근 판단 이력 조회를 생략했습니다.",
        }

    recent_decisions = memory_store.get_recent_decisions(
        user_id=user_id,
        stock_codes=stock_codes,
        sectors=sectors,
        key_signals=key_signals,
        limit=10,
        days=days,
        as_of=as_of,
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
        as_of=as_of,
    )

    return {
        "query_basis": {
            "stock_codes": stock_codes,
            "sectors": sectors,
            "key_signals": key_signals,
        },
        "recent_decisions": recent_decisions,
        "recent_loss_decisions": recent_loss_decisions,
        "lessons_aggregate": _aggregate_lessons(recent_decisions + recent_loss_decisions),
        "loss_pattern_brief": _summarize_loss_pattern(recent_loss_decisions),
        "similar_decisions_table": _brief_table(recent_decisions),
        "recent_post_mortems": _recent_post_mortems(recent_decisions),
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


# ────────────────────────────────────────────────────────────────────────────
# LLM 프롬프트 1급 노출용 정제 함수
#
# 원칙:
#   - LLM 호출 없이 결정론적 압축만 한다 (백테스트 재현성 보호).
#   - 자유 텍스트는 길이 상한으로 토큰 부담을 통제한다.
#   - 정형 메타(ai_judgment_id, user_id, order_id 등 LLM에 무의미한 식별자)는
#     이 단계에서 제거해 LLM 주의 분산을 막는다.
# ────────────────────────────────────────────────────────────────────────────


def _aggregate_lessons(
    decisions: list[PastDecision],
    top_n: int = 8,
) -> list[dict[str, Any]]:
    """post_mortem_lessons를 누적·중복제거하고 빈도 가중 top-N으로 추출한다.

    동일 lesson이 여러 회고에 반복 등장하면 occurred 값으로 우선순위를 노출해
    LLM이 빈도 높은 룰을 먼저 적용하게 유도한다.
    """
    counter: Counter[str] = Counter()
    for d in decisions:
        for lesson in (d.get("post_mortem_lessons") or []):
            normalized = lesson.strip()
            if normalized:
                counter[normalized] += 1

    return [
        {"lesson": lesson, "occurred": count}
        for lesson, count in counter.most_common(top_n)
    ]


def _summarize_loss_pattern(loss_decisions: list[PastDecision]) -> str:
    """손실 판단의 핵심 텍스트(판단 사유 + Bear 우려)만 한 줄/건으로 압축한다."""
    if not loss_decisions:
        return "(최근 손실 판단 없음)"

    lines: list[str] = []
    for d in loss_decisions[:5]:
        reason = (d.get("judgment_reason") or "").strip()[:_LOSS_TEXT_PREVIEW_CHARS]
        bear = (d.get("bear_claim") or "").strip()[:_LOSS_TEXT_PREVIEW_CHARS]
        pnl_rate = d.get("realized_profit_loss_rate")
        pnl_str = f"{pnl_rate * 100:+.1f}%" if pnl_rate is not None else "?"
        stock_code = d.get("stock_code", "?")
        lines.append(
            f"- {stock_code} ({pnl_str}): 판단={reason or '(없음)'} / Bear 우려={bear or '(없음)'}"
        )
    return "\n".join(lines)


def _brief_table(decisions: list[PastDecision]) -> list[dict[str, Any]]:
    """LLM에 무의미한 정형 메타(ai_judgment_id, order_id 등)를 제거하고
    핵심 정형 필드 + 짧은 자유 텍스트만 남긴 한 줄/건 표를 만든다.
    """
    table: list[dict[str, Any]] = []
    for d in decisions:
        table.append({
            "stock_code": d.get("stock_code"),
            "stock_name": d.get("stock_name"),
            "sector": d.get("sector"),
            "decision": d.get("decision"),
            "confidence": d.get("confidence_score"),
            "pnl_rate": d.get("realized_profit_loss_rate"),
            "reason": (d.get("judgment_reason") or "")[:_REASON_PREVIEW_CHARS],
        })
    return table


def _recent_post_mortems(
    decisions: list[PastDecision],
    top_n: int = 5,
) -> list[dict[str, Any]]:
    """summary와 lessons가 모두 채워진 회고만 골라 1급으로 노출한다.

    회고가 없는 판단은 의도적으로 제외해 LLM이 회고 자산에만 집중하게 한다.
    """
    selected: list[dict[str, Any]] = []
    for d in decisions:
        summary = d.get("post_mortem_summary")
        lessons = d.get("post_mortem_lessons")
        if not summary or not lessons:
            continue
        selected.append({
            "stock_code": d.get("stock_code"),
            "decision": d.get("decision"),
            "summary": summary,
            "lessons": lessons,
        })
        if len(selected) >= top_n:
            break
    return selected
