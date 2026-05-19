"""두 모드 결과의 통계적 비교 — paired McNemar + bootstrap CI.

DA framework가 직접 제공하지 않는 통계 검정 레이어. AI 팀이 작성.
발표 시 "Bull/Bear가 단일 에이전트보다 유의미하게 더 낫다" 주장의 근거.

scipy 미설치 환경 대비: McNemar는 binomial 정확검정 직접 구현.
"""
from dataclasses import dataclass
from math import comb
from typing import Sequence


@dataclass
class McNemarResult:
    b: int  # mode_a만 hit
    c: int  # mode_b만 hit
    p_value: float
    interpretation: str


def mcnemar_paired(
    mode_a_hits: Sequence[bool],
    mode_b_hits: Sequence[bool],
    label_a: str = "mode A",
    label_b: str = "mode B",
) -> McNemarResult:
    """McNemar's exact test (이항분포 기반).

    같은 trigger 인덱스의 두 모드 hit 결과를 받아 차이의 p-value를 반환.
    H0: 두 모드의 hit 확률이 같다.

    label_a / label_b: interpretation 문자열에 표시할 mode 라벨.
        호출자가 실제 mode 이름(예: "debate_1", "debate_2")을 넘기면
        결과 해석이 그대로 노출된다.
    """
    if len(mode_a_hits) != len(mode_b_hits):
        raise ValueError(
            f"mode_a({len(mode_a_hits)})와 mode_b({len(mode_b_hits)}) 길이가 같아야 합니다."
        )
    b = sum(1 for a, bb in zip(mode_a_hits, mode_b_hits) if a and not bb)
    c = sum(1 for a, bb in zip(mode_a_hits, mode_b_hits) if not a and bb)
    n = b + c
    if n == 0:
        return McNemarResult(b=0, c=0, p_value=1.0, interpretation="두 모드 결과가 동일")

    # 양측 binomial: 더 적은 쪽 기준 누적확률 * 2 (또는 1로 clip)
    k = min(b, c)
    one_sided = sum(comb(n, i) for i in range(k + 1)) / (2 ** n)
    p = min(1.0, 2 * one_sided)

    interp = _interpret_mcnemar(b, c, p, label_a, label_b)
    return McNemarResult(b=b, c=c, p_value=p, interpretation=interp)


def bootstrap_ci(
    values: Sequence[float],
    *,
    iterations: int = 1000,
    confidence: float = 0.95,
    seed: int = 42,
) -> tuple[float, float, float]:
    """비모수 부트스트랩 신뢰구간. mean(values)의 (lo, mid, hi) 반환.

    hit rate(0/1 시퀀스) 또는 수익률(float 시퀀스) 모두 적용 가능.
    scipy 없이 동작하도록 random.Random + 정렬만 사용.
    """
    import random as _r
    if not values:
        return (0.0, 0.0, 0.0)
    rng = _r.Random(seed)
    n = len(values)
    samples: list[float] = []
    for _ in range(iterations):
        resample = [values[rng.randrange(n)] for _ in range(n)]
        samples.append(sum(resample) / n)
    samples.sort()
    lo_idx = int((1 - confidence) / 2 * iterations)
    hi_idx = int((1 + confidence) / 2 * iterations) - 1
    return (samples[lo_idx], sum(values) / n, samples[hi_idx])


def _interpret_mcnemar(b: int, c: int, p: float, label_a: str, label_b: str) -> str:
    if p >= 0.05:
        return f"유의미한 차이 없음 (p={p:.3f})"
    winner = label_a if b > c else label_b
    return f"{winner}가 유의미하게 더 자주 hit (p={p:.3f})"
