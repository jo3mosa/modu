import os
import io
import json
import zipfile
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta

import requests
from dotenv import load_dotenv

load_dotenv()


# 작업 중단이 필요한 OpenDART 응답 status 코드
#   020: 일일 호출 한도 초과 (quota)
#   010, 011: 등록되지 않은 / 사용할 수 없는 키
#   012: 허용되지 않은 IP
# 일반 실패(미공시 013, 시스템 점검 800, 정의되지 않은 오류 900 등)와 분리해 caller가
# "키 교체 필요" 시그널을 명확히 인지할 수 있도록 한다.
CRITICAL_STATUSES = {"020", "010", "011", "012"}


class DartCriticalError(Exception):
    """OpenDART 치명적 응답 — 운영 개입(키 교체/IP 등록 등)이 필요하므로 작업 중단."""

    def __init__(self, status, message):
        self.status = status
        self.message = message or ""
        super().__init__(f"DART status={status}: {self.message}")


class DartApiClient:
    BASE_URL = "https://opendart.fss.or.kr/api"
    CORP_CODE_CACHE = "dart_corp_code.json"
    CORP_CODE_TTL_DAYS = 7
    HTTP_TIMEOUT = 10  # seconds — polling worker hang 방지

    # 표준 계정명 → (해당 statement 집합, DART 응답에서 매칭 가능한 원본 계정명 후보)
    #
    # 산업별 회계 양식 차이 흡수:
    #   · 일반 제조·서비스: "매출액"
    #   · 은행·보험·증권·자산운용: "영업수익" (이자/수수료/보험료 합산), "보험료수익"
    #   · 일부 기업: "수익(매출액)"
    # 매칭된 모든 원본 계정명은 표준명(키) 으로 정규화되어 반환되므로, 다운스트림 코드는
    # 산업 무관하게 동일한 키("매출액", "당기순이익" 등)로 접근 가능하다.
    #
    # IS 우선, IS 없을 때만 CIS 로 fallback — 첫 매칭이 IS 면 후속 CIS 매칭은 무시.
    ACCOUNT_ALIASES = {
        "자산총계":   ({"BS"},        {"자산총계"}),
        "부채총계":   ({"BS"},        {"부채총계"}),
        "자본총계":   ({"BS"},        {"자본총계"}),
        "유동자산":   ({"BS"},        {"유동자산"}),
        "유동부채":   ({"BS"},        {"유동부채"}),
        "매출액":     ({"IS", "CIS"}, {"매출액", "영업수익", "수익(매출액)", "보험료수익"}),
        "영업이익":   ({"IS", "CIS"}, {"영업이익", "영업이익(손실)"}),
        "당기순이익": ({"IS", "CIS"}, {"당기순이익", "당기순이익(손실)"}),
    }

    # 매칭 효율을 위한 역방향 인덱스: (sj_div, 원본 계정명) → 표준명
    _ACCOUNT_NAME_TO_STD = {
        (sj, alias): std
        for std, (sj_divs, aliases) in ACCOUNT_ALIASES.items()
        for sj in sj_divs
        for alias in aliases
    }

    def __init__(self):
        self.api_key = os.getenv("DART_API_KEY")
        if not self.api_key:
            raise ValueError("DART_API_KEY 환경변수가 설정되지 않았습니다.")
        self._corp_code_map = None

    # 공통 HTTP GET — timeout + 4xx/5xx 검증
    def _get(self, url, params=None):
        response = requests.get(url, params=params, timeout=self.HTTP_TIMEOUT)
        response.raise_for_status()
        return response

    # corp_code (8자리) ↔ stock_code (6자리) 매핑
    def _load_corp_code_map(self):
        if self._corp_code_map is not None:
            return self._corp_code_map

        if self._is_cache_valid():
            with open(self.CORP_CODE_CACHE, "r", encoding="utf-8") as f:
                self._corp_code_map = json.load(f)
            return self._corp_code_map

        url = f"{self.BASE_URL}/corpCode.xml"
        response = self._get(url, params={"crtfc_key": self.api_key})

        with zipfile.ZipFile(io.BytesIO(response.content)) as zf:
            with zf.open("CORPCODE.xml") as f:
                tree = ET.parse(f)

        mapping = {}
        for item in tree.iter("list"):
            stock_code = (item.findtext("stock_code") or "").strip()
            corp_code = (item.findtext("corp_code") or "").strip()
            if stock_code and corp_code:
                mapping[stock_code] = corp_code

        with open(self.CORP_CODE_CACHE, "w", encoding="utf-8") as f:
            json.dump(mapping, f)

        self._corp_code_map = mapping
        print(f"[INFO] corp_code 매핑 {len(mapping)}건 캐시 저장")
        return mapping

    def _is_cache_valid(self):
        if not os.path.exists(self.CORP_CODE_CACHE):
            return False
        mtime = datetime.fromtimestamp(os.path.getmtime(self.CORP_CODE_CACHE))
        return datetime.now() - mtime < timedelta(days=self.CORP_CODE_TTL_DAYS)

    def get_corp_code(self, stock_code):
        return self._load_corp_code_map().get(stock_code)

    # 단일회사 전체 재무제표 (연결 우선, 없으면 별도)
    def get_financial_accounts(self, corp_code, bsns_year, reprt_code="11011", fs_div="CFS"):
        url = f"{self.BASE_URL}/fnlttSinglAcntAll.json"
        params = {
            "crtfc_key": self.api_key,
            "corp_code": corp_code,
            "bsns_year": str(bsns_year),
            "reprt_code": reprt_code,
            "fs_div": fs_div,
        }
        data = self._get(url, params=params).json()

        status = data.get("status")
        # status 013 = 조회된 데이터 없음 → 연결재무제표가 없는 소형주이므로 별도(OFS)로 fallback
        if status == "013" and fs_div == "CFS":
            return self.get_financial_accounts(corp_code, bsns_year, reprt_code, fs_div="OFS")
        # quota/인증/IP 등 운영 개입 필요한 상태는 즉시 raise — caller가 일반 실패와 구분
        if status in CRITICAL_STATUSES:
            raise DartCriticalError(status, data.get("message"))
        # 그 외(미공시 013, 시스템 점검 800 등)는 stdout 에 status 노출 후 None 반환
        if status != "000":
            print(f"[ERROR] 재무제표 조회 실패 ({bsns_year}/{reprt_code}/{fs_div}): "
                  f"status={status}, message={data.get('message')}")
            return None

        accounts = {}
        for item in data.get("list", []):
            sj_div = (item.get("sj_div") or "").strip()
            name = (item.get("account_nm") or "").strip()
            std_name = self._ACCOUNT_NAME_TO_STD.get((sj_div, name))
            if std_name is None:
                continue
            amount_str = (item.get("thstrm_amount") or "").replace(",", "").strip()
            try:
                amount = int(amount_str)
            except (ValueError, TypeError):
                continue
            # IS 우선, IS 없을 때만 CIS로 fallback (이미 IS 값이 있으면 덮어쓰지 않음)
            if sj_div == "CIS" and std_name in accounts:
                continue
            # 동일 표준명에 여러 원본 계정명이 매칭되는 경우(예: 매출액·영업수익 둘 다 등장)
            # 이미 더 우선순위 높은 alias 값이 있으면 덮어쓰지 않음.
            # 응답 순서상 통상 표준 양식 계정이 먼저 등장하므로 first-wins 로 충분.
            if sj_div != "CIS" and std_name in accounts:
                continue
            accounts[std_name] = amount
        return accounts

    # 발행주식수 (보통주)
    def get_shares_outstanding(self, corp_code, bsns_year, reprt_code="11011"):
        # 주의: OpenDART 실제 endpoint는 "Sttus" (Status 약어)
        url = f"{self.BASE_URL}/stockTotqySttus.json"
        params = {
            "crtfc_key": self.api_key,
            "corp_code": corp_code,
            "bsns_year": str(bsns_year),
            "reprt_code": reprt_code,
        }
        data = self._get(url, params=params).json()

        status = data.get("status")
        if status in CRITICAL_STATUSES:
            raise DartCriticalError(status, data.get("message"))
        if status != "000":
            print(f"[ERROR] 주식수 조회 실패 ({bsns_year}/{reprt_code}): "
                  f"status={status}, message={data.get('message')}")
            return None

        for item in data.get("list", []):
            if "보통주" not in (item.get("se") or ""):
                continue
            # istc_totqy = 발행한 주식의 총수
            shares_str = (item.get("istc_totqy") or "").replace(",", "").strip()
            try:
                return int(shares_str)
            except (ValueError, TypeError):
                continue
        return None

    # 공시 목록
    def get_disclosures(self, corp_code, bgn_de, end_de, page_count=100):
        url = f"{self.BASE_URL}/list.json"
        params = {
            "crtfc_key": self.api_key,
            "corp_code": corp_code,
            "bgn_de": bgn_de,
            "end_de": end_de,
            "page_count": str(page_count),
        }
        data = self._get(url, params=params).json()

        status = data.get("status")
        if status in CRITICAL_STATUSES:
            raise DartCriticalError(status, data.get("message"))
        if status not in ("000", "013"):  # 013 = 조회된 데이터 없음 (정상)
            print(f"[ERROR] 공시 조회 실패: status={status}, message={data.get('message')}")
            return []
        return data.get("list", [])


if __name__ == "__main__":
    client = DartApiClient()
    code = client.get_corp_code("005930")
    print(f"삼성전자 corp_code: {code}")
    accounts = client.get_financial_accounts(code, 2024)
    print(json.dumps(accounts, indent=2, ensure_ascii=False))
    shares = client.get_shares_outstanding(code, 2024)
    print(f"발행주식수: {shares:,}" if shares is not None else "발행주식수: 조회 실패")
