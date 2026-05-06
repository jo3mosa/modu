import json
from datetime import datetime, timezone

from dart_api_client import DartApiClient
from kis_api_client import KisApiClient

from datetime import timedelta


# 공시 임팩트 분류 (룰베이스 시작용 — 추후 LLM 분류기로 대체 가능)
URGENT_KEYWORDS = ("조회공시", "거래정지", "관리종목", "감사의견거절", "소송", "회생절차")
POSITIVE_KEYWORDS = ("단일판매ㆍ공급계약체결", "자기주식취득", "무상증자", "현금ㆍ현물배당", "특허취득")
NEGATIVE_KEYWORDS = ("감사의견거절", "관리종목", "유상증자결정(제3자배정)", "전환사채권발행", "소송", "횡령")


def classify_impact(title):
    if any(kw in title for kw in NEGATIVE_KEYWORDS):
        return "negative"
    if any(kw in title for kw in POSITIVE_KEYWORDS):
        return "positive"
    return "neutral"


def has_urgent(disclosures):
    return any(any(kw in (d.get("report_nm") or "") for kw in URGENT_KEYWORDS) for d in disclosures)


# 임곗값은 한국 시장 평균을 기준으로 보수적으로 설정 (추후 섹터별로 분리 권장)
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
    equity = accounts.get("자본총계")
    debt = accounts.get("부채총계")
    current_assets = accounts.get("유동자산")
    current_liab = accounts.get("유동부채")

    # 자본잠식
    if not equity or equity <= 0:
        return "risky"

    debt_ratio = (debt / equity * 100) if debt else 0
    current_ratio = (current_assets / current_liab * 100) if current_liab else None

    if debt_ratio > 200:
        return "risky"
    if current_ratio is not None and current_ratio < 100:
        return "risky"
    if debt_ratio < 100 and (current_ratio is None or current_ratio > 150):
        return "stable"
    return "moderate"


def _format_disclosure(d):
    # OpenDART list.json은 접수 '시각'을 제공하지 않음 → 접수일자만 노출
    # 정확한 HH:MM이 필요하면 dart.fss.or.kr 상세 페이지 스크래핑 필요
    return {
        "time": d.get("rcept_dt", ""),
        "title": d.get("report_nm", ""),
        "impact_level": classify_impact(d.get("report_nm", "")),
    }


def calculate_fundamental(stock_code, bsns_year=None):
    """OpenDART 재무제표 + KIS 현재가로 fundamental/event 섹션을 계산."""
    if bsns_year is None:
        # 직전 사업연도 (사업보고서는 익년 3월말 공시)
        bsns_year = datetime.now().year - 1

    dart = DartApiClient()
    kis = KisApiClient()

    # 1) 종목코드 → corp_code
    corp_code = dart.get_corp_code(stock_code)
    if not corp_code:
        return {"error": f"corp_code를 찾을 수 없습니다: {stock_code}"}

    # 2) 재무제표 (당해 + 직전연도 — YoY 성장성 계산용)
    accounts_curr = dart.get_financial_accounts(corp_code, bsns_year)
    accounts_prev = dart.get_financial_accounts(corp_code, bsns_year - 1)
    if not accounts_curr:
        return {"error": f"{bsns_year}년 사업보고서 재무제표를 찾을 수 없습니다."}

    # 3) 발행주식수
    shares = dart.get_shares_outstanding(corp_code, bsns_year)

    # 4) KIS 현재가
    snapshot = kis.get_realtime_snapshot(stock_code)
    current_price = snapshot["close"] if snapshot else None

    # 5) 핵심 지표 계산
    net_income = accounts_curr.get("당기순이익")
    equity = accounts_curr.get("자본총계")

    eps = (net_income / shares) if (net_income and shares) else None
    bps = (equity / shares) if (equity and shares and shares > 0) else None
    per = (current_price / eps) if (current_price and eps and eps > 0) else None
    pbr = (current_price / bps) if (current_price and bps and bps > 0) else None
    roe = (net_income / equity * 100) if (net_income and equity and equity > 0) else None

    # 6) 공시 (오늘자)
    today = datetime.now().strftime("%Y%m%d")
    week_ago = (datetime.now() - timedelta(days=7)).strftime("%Y%m%d")
    disclosures = dart.get_disclosures(corp_code, week_ago, today)

    return {
        "stock_code": stock_code,
        "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "fiscal_year": bsns_year,
        "current_price": current_price,
        "fundamental": {
            "valuation": {
                "per": round(per, 2) if per else None,
                "pbr": round(pbr, 2) if pbr else None,
                "status": classify_valuation(per, pbr),
            },
            "profitability": {
                "roe": round(roe, 2) if roe else None,
                "status": classify_profitability(roe),
            },
            "growth": {
                "status": classify_growth(accounts_curr, accounts_prev),
            },
            "stability": {
                "status": classify_stability(accounts_curr),
            },
        },
        "event": {
            "has_urgent_issue": has_urgent(disclosures),
            "recent_disclosures": [_format_disclosure(d) for d in disclosures[:10]],
        },
    }


if __name__ == "__main__":
    result = calculate_fundamental("005930")
    print(json.dumps(result, indent=2, ensure_ascii=False))