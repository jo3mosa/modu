"""disclosure_collector

OpenDART API 3분 polling → 공시 분류·impact_level 판단 → Redis `event:{stock_code}` 갱신.
독립 실행 수집 모듈.
"""


# 공시 임팩트 분류 (룰베이스 시작용 — 추후 LLM 분류기로 대체 가능)
URGENT_KEYWORDS = ("조회공시", "거래정지", "관리종목", "감사의견거절", "소송", "회생절차")
POSITIVE_KEYWORDS = ("단일판매ㆍ공급계약체결", "자기주식취득", "무상증자", "현금ㆍ현물배당", "특허취득")
NEGATIVE_KEYWORDS = ("감사의견거절", "관리종목", "유상증자결정(제3자배정)", "전환사채권발행", "소송", "횡령")


def classify_impact(title: str) -> str:
    if any(kw in title for kw in NEGATIVE_KEYWORDS):
        return "negative"
    if any(kw in title for kw in POSITIVE_KEYWORDS):
        return "positive"
    return "neutral"


def has_urgent(disclosures: list[dict]) -> bool:
    return any(any(kw in (d.get("report_nm") or "") for kw in URGENT_KEYWORDS) for d in disclosures)


def format_disclosure(d: dict) -> dict:
    # OpenDART list.json은 접수 '시각'을 제공하지 않음 → 접수일자만 노출.
    # 정확한 HH:MM이 필요하면 dart.fss.or.kr 상세 페이지 스크래핑 필요.
    return {
        "time": d.get("rcept_dt", ""),
        "title": d.get("report_nm", ""),
        "impact_level": classify_impact(d.get("report_nm", "")),
    }


def run() -> None:
    raise NotImplementedError("disclosure_collector.run is not implemented yet")


if __name__ == "__main__":
    run()
