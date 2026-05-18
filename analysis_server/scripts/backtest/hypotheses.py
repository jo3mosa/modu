"""hypotheses — 트리거 백테스트의 사전 등록(pre-registered) 가설 목록.

⚠️ Pre-registration 원칙:
    이 파일은 23-24년 백테스트 결과를 보기 전에 확정되어야 한다.
    결과를 본 후 가설을 추가·제거·수정하면 다중검정 보정이 무의미해진다.
    수정이 불가피하면 git log 에 명시하고 사유를 남긴다.

구조:
    SINGLE_HYPOTHESES  — A 단독 19개. 효과 측정만 (Lv.0 baseline).
    DOUBLE_HYPOTHESES  — B 2지표 조합 26개. 통계 검정 대상.
    TRIPLE_HYPOTHESES  — C 트리플 조합 10개. 통계 검정 대상.

검정 대상 총 36개 — FDR 5% 보정 시 sweet spot.

원칙:
    1. State (지속 상태) 는 종목 필터, Transition (시점 변화) 만 트리거.
       - Filter: ROE 상위 풀, PBR 하위 풀, SMA 정배열 풀
       - Trigger: RSI<30 진입, MACD 골든, 공시 발생, 거래량 급증 등
    2. 호재/악재 · 양/음은 대칭 mirror 로 짝지음.
    3. 모든 트리거는 "임계 진입 시점 1번" 발화 (며칠 연속 RSI<30 이어도 첫날만).
    4. main_horizon 은 T+5 (5거래일 후 종가 기준 수익률) 가 기본.
       이벤트성 신호도 T+5 메인, 펀더멘털 조합은 T+20 메인.

JSON export:
    python -m scripts.backtest.hypotheses
    → 같은 폴더에 hypotheses.json 갱신. 발표·문서용.
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from pathlib import Path

# ────────────────────────────────────────────────────────────────────
# 데이터 모델
# ────────────────────────────────────────────────────────────────────


@dataclass
class Hypothesis:
    id: str                          # "A1", "B7", "C3" 등
    name: str                        # 한글 이름
    category: str                    # "single" / "double" / "triple"
    kind: str                        # "transition" / "filter+transition" / "event" / "media" / etc.
    components: list[str]            # 구성 신호 (사람이 읽는 형태)
    expected_direction: str          # "positive" / "negative" / "two_sided"
    main_horizon: int                # 메인 평가 horizon (거래일)
    additional_horizons: list[int]   # 보조 horizon 들
    test_status: str                 # "measurement_only" (A) / "hypothesis_test" (B/C)
    mirror_of: str | None            # 대칭 짝의 id (없으면 None)
    rationale: str                   # 한 줄 도메인 근거


# ────────────────────────────────────────────────────────────────────
# A. 단독 신호 (19개) — 효과 측정만, 가설 검정 X
# ────────────────────────────────────────────────────────────────────

SINGLE_HYPOTHESES: list[Hypothesis] = [
    # ── 과매수·과매도 (mean-reversion) ──
    Hypothesis("A1", "RSI<30 진입", "single", "transition",
               ["RSI<30 transition"], "positive", 5, [1, 20],
               "measurement_only", "A2",
               "기술적 과매도 → 반등 기대"),
    Hypothesis("A2", "RSI>70 진입", "single", "transition",
               ["RSI>70 transition"], "negative", 5, [1, 20],
               "measurement_only", "A1",
               "기술적 과매수 → 조정 기대"),
    Hypothesis("A3", "MFI<20 진입", "single", "transition",
               ["MFI<20 transition"], "positive", 5, [1, 20],
               "measurement_only", "A4",
               "자금 흐름 과매도 → 반등 기대"),
    Hypothesis("A4", "MFI>80 진입", "single", "transition",
               ["MFI>80 transition"], "negative", 5, [1, 20],
               "measurement_only", "A3",
               "자금 흐름 과매수 → 조정 기대"),

    # ── 추세·모멘텀 ──
    Hypothesis("A5", "MACD 골든크로스", "single", "transition",
               ["MACD 골든"], "positive", 5, [1, 20],
               "measurement_only", "A6",
               "모멘텀 상승 전환"),
    Hypothesis("A6", "MACD 데드크로스", "single", "transition",
               ["MACD 데드"], "negative", 5, [1, 20],
               "measurement_only", "A5",
               "모멘텀 하락 전환"),
    Hypothesis("A7", "SMA20 상향 돌파", "single", "transition",
               ["종가>SMA20 transition"], "positive", 5, [1, 20],
               "measurement_only", "A8",
               "단기 추세 상승 전환"),
    Hypothesis("A8", "SMA20 하향 돌파", "single", "transition",
               ["종가<SMA20 transition"], "negative", 5, [1, 20],
               "measurement_only", "A7",
               "단기 추세 하락 전환"),

    # ── 변동성·범위 ──
    Hypothesis("A9", "볼린저 하단 터치", "single", "transition",
               ["가격<=BB_lower"], "positive", 5, [1, 20],
               "measurement_only", "A10",
               "변동성 범위 하단 → 반등 기대"),
    Hypothesis("A10", "볼린저 상단 터치", "single", "transition",
                ["가격>=BB_upper"], "negative", 5, [1, 20],
                "measurement_only", "A9",
                "변동성 범위 상단 → 조정 기대"),
    Hypothesis("A11", "볼린저 밴드폭 급증", "single", "transition",
                ["BB_width 평소 대비 N배"], "two_sided", 5, [1, 20],
                "measurement_only", None,
                "변동성 확장 = 큰 가격 움직임 임박 (방향성 X)"),
    Hypothesis("A12", "ATR 급증", "single", "transition",
                ["ATR 평소 대비 N배"], "two_sided", 5, [1, 20],
                "measurement_only", None,
                "변동성 확장 신호"),

    # ── 거래량 ──
    Hypothesis("A13", "거래량 급증", "single", "transition",
                ["거래량 > 20일평균 × N"], "two_sided", 5, [1, 20],
                "measurement_only", None,
                "시장 관심 급증 (방향성은 가격으로 결정)"),

    # ── 이벤트 ──
    Hypothesis("A14", "호재공시 발생", "single", "event",
                ["impact_level=positive"], "positive", 5, [1, 20],
                "measurement_only", "A15",
                "긍정적 공시"),
    Hypothesis("A15", "악재공시 발생", "single", "event",
                ["impact_level=negative"], "negative", 5, [1, 20],
                "measurement_only", "A14",
                "부정적 공시"),
    Hypothesis("A16", "URGENT 공시 발생", "single", "event",
                ["URGENT 키워드 매칭"], "negative", 5, [1, 20],
                "measurement_only", None,
                "거래정지/관리종목/상장폐지 등 긴급 사안"),

    # ── 미디어 ──
    Hypothesis("A17", "뉴스감성 강양 진입", "single", "media",
                ["sentiment_score 임계 상회 transition"], "positive", 5, [1, 20],
                "measurement_only", "A18",
                "미디어 호재 점수 강세"),
    Hypothesis("A18", "뉴스감성 강음 진입", "single", "media",
                ["sentiment_score 임계 하회 transition"], "negative", 5, [1, 20],
                "measurement_only", "A17",
                "미디어 악재 점수 강세"),
    Hypothesis("A19", "뉴스감성 급변", "single", "media",
                ["|Δsentiment| > 임계"], "two_sided", 5, [1, 20],
                "measurement_only", None,
                "감성 점수 급변 (방향성 X)"),
]


# ────────────────────────────────────────────────────────────────────
# B. 2지표 조합 (26개) — 통계 검정 대상
# ────────────────────────────────────────────────────────────────────

DOUBLE_HYPOTHESES: list[Hypothesis] = [
    # ── 이중 mean-reversion ──
    Hypothesis("B1", "RSI<30 & MFI<20", "double", "transition",
               ["RSI<30 transition", "MFI<20 transition"], "positive", 5, [1, 20],
               "hypothesis_test", "B4",
               "이중 과매도 → 반등 강화 (모멘텀+자금흐름 동조)"),
    Hypothesis("B2", "RSI<30 & 볼린저 하단", "double", "transition",
               ["RSI<30 transition", "BB_lower 터치"], "positive", 5, [1, 20],
               "hypothesis_test", "B5",
               "모멘텀 과매도 + 가격 범위 하단 동조"),
    Hypothesis("B3", "MFI<20 & 볼린저 하단", "double", "transition",
               ["MFI<20 transition", "BB_lower 터치"], "positive", 5, [1, 20],
               "hypothesis_test", "B6",
               "자금흐름 과매도 + 가격 범위 하단 동조"),
    Hypothesis("B4", "RSI>70 & MFI>80", "double", "transition",
               ["RSI>70 transition", "MFI>80 transition"], "negative", 5, [1, 20],
               "hypothesis_test", "B1",
               "이중 과매수 → 조정 강화"),
    Hypothesis("B5", "RSI>70 & 볼린저 상단", "double", "transition",
               ["RSI>70 transition", "BB_upper 터치"], "negative", 5, [1, 20],
               "hypothesis_test", "B2",
               "모멘텀 과매수 + 가격 범위 상단 동조"),
    Hypothesis("B6", "MFI>80 & 볼린저 상단", "double", "transition",
               ["MFI>80 transition", "BB_upper 터치"], "negative", 5, [1, 20],
               "hypothesis_test", "B3",
               "자금흐름 과매수 + 가격 범위 상단 동조"),

    # ── Momentum transition mirror ──
    Hypothesis("B7", "MACD 골든 & SMA20 상향 돌파", "double", "transition",
               ["MACD 골든", "종가>SMA20 transition"], "positive", 5, [1, 20],
               "hypothesis_test", "B8",
               "모멘텀+단기추세 동시 상승 전환"),
    Hypothesis("B8", "MACD 데드 & SMA20 하향 돌파", "double", "transition",
               ["MACD 데드", "종가<SMA20 transition"], "negative", 5, [1, 20],
               "hypothesis_test", "B7",
               "모멘텀+단기추세 동시 하락 전환"),

    # ── 이벤트 × 거래량 mirror ──
    Hypothesis("B18", "호재공시 & 거래량 급증", "double", "event+transition",
               ["호재공시", "거래량 급증"], "positive", 5, [1, 20],
               "hypothesis_test", "B19",
               "호재 + 시장 동조 확인"),
    Hypothesis("B19", "악재공시 & 거래량 급증", "double", "event+transition",
               ["악재공시", "거래량 급증"], "negative", 5, [1, 20],
               "hypothesis_test", "B18",
               "악재 + 시장 동조 확인"),

    # ── 이벤트 × 미디어 mirror ──
    Hypothesis("B21", "호재공시 & 뉴스감성 양", "double", "event+media",
               ["호재공시", "sentiment 양 (윈도우 내)"], "positive", 5, [1, 20],
               "hypothesis_test", "B22",
               "호재 + 미디어 동조"),
    Hypothesis("B22", "악재공시 & 뉴스감성 음", "double", "event+media",
               ["악재공시", "sentiment 음 (윈도우 내)"], "negative", 5, [1, 20],
               "hypothesis_test", "B21",
               "악재 + 미디어 동조"),

    # ── 미디어 × 거래량 mirror ──
    Hypothesis("B33", "뉴스감성 양 & 거래량 급증", "double", "media+transition",
               ["sentiment 양 진입", "거래량 급증"], "positive", 5, [1, 20],
               "hypothesis_test", "B34",
               "미디어 호재 + 시장 반응"),
    Hypothesis("B34", "뉴스감성 음 & 거래량 급증", "double", "media+transition",
               ["sentiment 음 진입", "거래량 급증"], "negative", 5, [1, 20],
               "hypothesis_test", "B33",
               "미디어 악재 + 시장 반응 (panic selling)"),

    # ── 이벤트 × 기술 mirror (B23-26) ──
    Hypothesis("B23", "호재공시 & SMA20 상향 돌파", "double", "event+transition",
               ["호재공시", "종가>SMA20 transition (윈도우 내)"], "positive", 5, [1, 20],
               "hypothesis_test", "B24",
               "호재 + 단기 추세 동조 (smart money 진입 가능)"),
    Hypothesis("B24", "악재공시 & SMA20 하향 돌파", "double", "event+transition",
               ["악재공시", "종가<SMA20 transition (윈도우 내)"], "negative", 5, [1, 20],
               "hypothesis_test", "B23",
               "악재 + 단기 추세 동조"),
    Hypothesis("B25", "호재공시 & MACD 골든", "double", "event+transition",
               ["호재공시", "MACD 골든 (윈도우 내)"], "positive", 5, [1, 20],
               "hypothesis_test", "B26",
               "호재 + 모멘텀 동조"),
    Hypothesis("B26", "악재공시 & MACD 데드", "double", "event+transition",
               ["악재공시", "MACD 데드 (윈도우 내)"], "negative", 5, [1, 20],
               "hypothesis_test", "B25",
               "악재 + 모멘텀 동조"),

    # ── 미디어 × 기술 mirror (B29-32) ──
    Hypothesis("B29", "뉴스감성 양 & SMA20 상향 돌파", "double", "media+transition",
               ["sentiment 양 진입", "종가>SMA20 transition (윈도우 내)"], "positive", 5, [1, 20],
               "hypothesis_test", "B30",
               "미디어 호재 + 단기 추세 동조"),
    Hypothesis("B30", "뉴스감성 음 & SMA20 하향 돌파", "double", "media+transition",
               ["sentiment 음 진입", "종가<SMA20 transition (윈도우 내)"], "negative", 5, [1, 20],
               "hypothesis_test", "B29",
               "미디어 악재 + 단기 추세 동조"),
    Hypothesis("B31", "뉴스감성 양 & MACD 골든", "double", "media+transition",
               ["sentiment 양 진입", "MACD 골든 (윈도우 내)"], "positive", 5, [1, 20],
               "hypothesis_test", "B32",
               "미디어 호재 + 모멘텀 동조"),
    Hypothesis("B32", "뉴스감성 음 & MACD 데드", "double", "media+transition",
               ["sentiment 음 진입", "MACD 데드 (윈도우 내)"], "negative", 5, [1, 20],
               "hypothesis_test", "B31",
               "미디어 악재 + 모멘텀 동조"),

    # ── Filter × Transition (펀더멘털 학계 표준) ──
    Hypothesis("B35", "ROE 상위 풀 ∩ RSI<30", "double", "filter+transition",
               ["filter: ROE 상위 N%", "trigger: RSI<30 transition"], "positive", 20, [5, 60],
               "hypothesis_test", None,
               "퀄리티 종목 풀에서 저가 진입 (Quality at low price)"),
    Hypothesis("B38", "PBR 하위 풀 ∩ MACD 골든", "double", "filter+transition",
               ["filter: PBR 하위 N%", "trigger: MACD 골든"], "positive", 20, [5, 60],
               "hypothesis_test", None,
               "Fama-French Value+Momentum 학계 표준"),

    # ── 역설적 가설 (방향 사전 미정, 양측 검정) ──
    Hypothesis("B27", "악재공시 & RSI<30", "double", "event+transition",
               ["악재공시", "RSI<30 (이미 과매도)"], "two_sided", 5, [1, 20],
               "hypothesis_test", "B28",
               "이미 과매도 + 추가 악재 → 반등? 추가 하락? 방향 불확실"),
    Hypothesis("B28", "호재공시 & RSI>70", "double", "event+transition",
               ["호재공시", "RSI>70 (이미 과매수)"], "two_sided", 5, [1, 20],
               "hypothesis_test", "B27",
               "이미 과매수 + 추가 호재 → 추가 상승? 차익실현? 방향 불확실"),
]


# ────────────────────────────────────────────────────────────────────
# C. 트리플 조합 (10개) — 통계 검정 대상
# ────────────────────────────────────────────────────────────────────

TRIPLE_HYPOTHESES: list[Hypothesis] = [
    # ── Mean-reversion + 거래량 mirror ──
    Hypothesis("C1", "RSI<30 & MFI<20 & 거래량 급증", "triple", "transition",
               ["RSI<30", "MFI<20", "거래량 급증"], "positive", 5, [1, 20],
               "hypothesis_test", "C2",
               "이중 과매도 + 시장 동조 (B1 강화)"),
    Hypothesis("C2", "RSI>70 & MFI>80 & 거래량 급증", "triple", "transition",
               ["RSI>70", "MFI>80", "거래량 급증"], "negative", 5, [1, 20],
               "hypothesis_test", "C1",
               "이중 과매수 + 시장 동조 (B4 강화)"),

    # ── 단일 reversion + 볼린저 + 거래량 mirror ──
    Hypothesis("C3", "RSI<30 & 볼린저 하단 & 거래량 급증", "triple", "transition",
               ["RSI<30", "BB_lower 터치", "거래량 급증"], "positive", 5, [1, 20],
               "hypothesis_test", "C4",
               "패닉 바닥 신호 (B2 강화)"),
    Hypothesis("C4", "RSI>70 & 볼린저 상단 & 거래량 급증", "triple", "transition",
               ["RSI>70", "BB_upper 터치", "거래량 급증"], "negative", 5, [1, 20],
               "hypothesis_test", "C3",
               "과매수 가속 신호 (B5 강화)"),

    # ── 트리플 과매도/매수 (거래량 없음) ──
    Hypothesis("C5", "RSI<30 & MFI<20 & 볼린저 하단", "triple", "transition",
               ["RSI<30", "MFI<20", "BB_lower"], "positive", 5, [1, 20],
               "hypothesis_test", "C6",
               "트리플 과매도 — B1+B2+B3 동시 (거래량 X)"),
    Hypothesis("C6", "RSI>70 & MFI>80 & 볼린저 상단", "triple", "transition",
               ["RSI>70", "MFI>80", "BB_upper"], "negative", 5, [1, 20],
               "hypothesis_test", "C5",
               "트리플 과매수 — B4+B5+B6 동시"),

    # ── Momentum 트리플 mirror ──
    Hypothesis("C7", "MACD 골든 & SMA20 상향 & 거래량 급증", "triple", "transition",
               ["MACD 골든", "종가>SMA20 transition", "거래량 급증"], "positive", 5, [1, 20],
               "hypothesis_test", "C8",
               "모멘텀+추세+동조 (B7 강화)"),
    Hypothesis("C8", "MACD 데드 & SMA20 하향 & 거래량 급증", "triple", "transition",
               ["MACD 데드", "종가<SMA20 transition", "거래량 급증"], "negative", 5, [1, 20],
               "hypothesis_test", "C7",
               "모멘텀+추세+동조 하락 (B8 강화)"),

    # ── 이벤트 3중 확인 mirror ──
    Hypothesis("C9", "호재공시 & 거래량 급증 & 뉴스감성 양", "triple", "event+transition+media",
               ["호재공시", "거래량 급증", "sentiment 양 (윈도우)"], "positive", 5, [1, 20],
               "hypothesis_test", "C10",
               "사건+시장+미디어 3중 확인 — 가장 강한 호재 신호"),
    Hypothesis("C10", "악재공시 & 거래량 급증 & 뉴스감성 음", "triple", "event+transition+media",
                ["악재공시", "거래량 급증", "sentiment 음 (윈도우)"], "negative", 5, [1, 20],
                "hypothesis_test", "C9",
                "사건+시장+미디어 3중 확인 — 가장 강한 악재 신호"),
]


# ────────────────────────────────────────────────────────────────────
# 통합 API + 검증
# ────────────────────────────────────────────────────────────────────

ALL_HYPOTHESES: list[Hypothesis] = (
    SINGLE_HYPOTHESES + DOUBLE_HYPOTHESES + TRIPLE_HYPOTHESES
)

HYPOTHESES_BY_ID: dict[str, Hypothesis] = {h.id: h for h in ALL_HYPOTHESES}


def get_test_targets() -> list[Hypothesis]:
    """통계 검정 대상 (B + C) — 다중검정 보정 적용 단위."""
    return [h for h in ALL_HYPOTHESES if h.test_status == "hypothesis_test"]


def get_measurement_only() -> list[Hypothesis]:
    """효과 측정만 (A, Lv.0 baseline) — 다중검정 보정 X."""
    return [h for h in ALL_HYPOTHESES if h.test_status == "measurement_only"]


def _self_check() -> None:
    """가설 정의의 내부 정합성 점검 — import 시 자동 실행."""
    seen_ids: set[str] = set()
    for h in ALL_HYPOTHESES:
        # ID 중복 금지.
        if h.id in seen_ids:
            raise ValueError(f"중복 가설 ID: {h.id}")
        seen_ids.add(h.id)
        # mirror_of 가 존재하면 그 id 도 정의되어 있어야 함.
        if h.mirror_of and h.mirror_of not in HYPOTHESES_BY_ID:
            # _self_check 시점에 아직 dict 구축 전이면 통과 — 두 번째 패스에서 검증.
            pass
        # expected_direction 유효성.
        if h.expected_direction not in ("positive", "negative", "two_sided"):
            raise ValueError(f"{h.id}: expected_direction 잘못됨")
        # test_status 유효성.
        if h.test_status not in ("measurement_only", "hypothesis_test"):
            raise ValueError(f"{h.id}: test_status 잘못됨")

    # mirror 짝 양방향 확인.
    for h in ALL_HYPOTHESES:
        if h.mirror_of:
            mate = HYPOTHESES_BY_ID.get(h.mirror_of)
            if mate is None:
                raise ValueError(f"{h.id}: mirror_of={h.mirror_of} 가 정의에 없음")
            if mate.mirror_of != h.id:
                raise ValueError(
                    f"{h.id} ↔ {h.mirror_of}: mirror 양방향 일관성 깨짐"
                )


_self_check()


# ────────────────────────────────────────────────────────────────────
# JSON Export — 발표·문서용
# ────────────────────────────────────────────────────────────────────

JSON_PATH = Path(__file__).parent / "hypotheses.json"


def export_json(path: Path = JSON_PATH) -> None:
    payload = {
        "version": "1.0",
        "registered_at": "2026-05-17",
        "principles": [
            "State 는 종목 필터, Transition 만 트리거",
            "호재/악재 · 양/음은 대칭 mirror",
            "트리거는 임계 진입 시점 1번 발화 (며칠 연속이어도 첫날만)",
            "main_horizon T+5 기본, 펀더멘털 조합은 T+20",
        ],
        "counts": {
            "single_measurement_only": len(SINGLE_HYPOTHESES),
            "double_hypothesis_test": len(DOUBLE_HYPOTHESES),
            "triple_hypothesis_test": len(TRIPLE_HYPOTHESES),
            "test_targets_total": len(get_test_targets()),
        },
        "hypotheses": [asdict(h) for h in ALL_HYPOTHESES],
    }
    path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(f"✓ {len(ALL_HYPOTHESES)} 가설 → {path}")


def main():
    print(f"단독 (측정만): {len(SINGLE_HYPOTHESES)}")
    print(f"2지표 조합 (검정): {len(DOUBLE_HYPOTHESES)}")
    print(f"트리플 (검정): {len(TRIPLE_HYPOTHESES)}")
    print(f"검정 대상 총: {len(get_test_targets())}")
    print(f"전체: {len(ALL_HYPOTHESES)}")
    export_json()


if __name__ == "__main__":
    main()
