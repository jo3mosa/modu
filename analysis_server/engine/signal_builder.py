"""signal_builder

Redis(`technical`, `event`, `sentiment`) + DB(fundamental) → Analysis Signal 조합.
1분 주기 엔진 사이클의 첫 단계.

raw 재무 수치를 분류 카테고리(valuation/profitability/growth/stability)로 매핑하는
classifier들을 보유한다. 임곗값은 한국 시장 평균 기준 보수적 설정 — 추후 섹터별 분리 권장.
"""


def classify_valuation(per, pbr):
    if per is None or pbr is None:
        return "unknown"
    if per < 10 and pbr < 1.0:
        return "undervalued"
    if per > 25 or pbr > 3.0:
        return "overvalued"
    return "fair"


def classify_profitability(roe):
    if roe is None:
        return "unknown"
    if roe >= 15:
        return "high_margin"
    if roe >= 8:
        return "normal"
    return "low_margin"


def classify_growth(curr, prev):
    if not curr or not prev:
        return "unknown"
    rev_curr, rev_prev = curr.get("매출액"), prev.get("매출액")
    op_curr, op_prev = curr.get("영업이익"), prev.get("영업이익")
    if not rev_curr or not rev_prev:
        return "unknown"

    rev_growth = (rev_curr - rev_prev) / abs(rev_prev) * 100
    op_growth = ((op_curr - op_prev) / abs(op_prev) * 100) if (op_curr and op_prev) else 0

    if rev_growth >= 20 and op_growth >= 20:
        return "high_growth"
    if rev_growth >= 5:
        return "steady_growth"
    if rev_growth < -5:
        return "declining"
    return "stagnant"


def classify_stability(accounts):
    # 필수 계정 결측 시 unknown — 결측을 stable/moderate로 오분류 방지
    required = ("자본총계", "부채총계", "유동자산", "유동부채")
    if any(accounts.get(k) is None for k in required):
        return "unknown"

    equity = accounts["자본총계"]
    debt = accounts["부채총계"]
    current_assets = accounts["유동자산"]
    current_liab = accounts["유동부채"]

    # 자본잠식
    if equity <= 0:
        return "risky"

    debt_ratio = debt / equity * 100
    current_ratio = (current_assets / current_liab * 100) if current_liab > 0 else None

    if debt_ratio > 200:
        return "risky"
    if current_ratio is not None and current_ratio < 100:
        return "risky"
    if debt_ratio < 100 and (current_ratio is None or current_ratio > 150):
        return "stable"
    return "moderate"


def build(stock_code: str):
    raise NotImplementedError("signal_builder.build is not implemented yet")
