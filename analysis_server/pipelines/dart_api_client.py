import os
import io
import json
import zipfile
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta

import requests
from dotenv import load_dotenv

load_dotenv()


class DartApiClient:
    BASE_URL = "https://opendart.fss.or.kr/api"
    CORP_CODE_CACHE = "dart_corp_code.json"
    CORP_CODE_TTL_DAYS = 7

    # 단일회사 전체 재무제표에서 추출할 (statement, account_nm) 쌍
    # sj_div: BS=재무상태표, IS=손익계산서, CIS=포괄손익계산서
    # → account_nm은 여러 재무제표에 중복 등장하므로 반드시 sj_div로 disambiguate
    TARGET_ACCOUNTS = {
        ("BS", "자산총계"),
        ("BS", "부채총계"),
        ("BS", "자본총계"),
        ("BS", "유동자산"),
        ("BS", "유동부채"),
        ("IS", "매출액"),
        ("IS", "영업이익"),
        ("IS", "당기순이익"),
        # 일부 기업은 IS 대신 CIS만 제공 → fallback
        ("CIS", "매출액"),
        ("CIS", "영업이익"),
        ("CIS", "당기순이익"),
    }

    def __init__(self):
        self.api_key = os.getenv("DART_API_KEY")
        if not self.api_key:
            raise ValueError("DART_API_KEY 환경변수가 설정되지 않았습니다.")
        self._corp_code_map = None

    # corp_code (8자리) ↔ stock_code (6자리) 매핑
    def _load_corp_code_map(self):
        if self._corp_code_map is not None:
            return self._corp_code_map

        if self._is_cache_valid():
            with open(self.CORP_CODE_CACHE, "r", encoding="utf-8") as f:
                self._corp_code_map = json.load(f)
            return self._corp_code_map

        url = f"{self.BASE_URL}/corpCode.xml"
        response = requests.get(url, params={"crtfc_key": self.api_key})
        response.raise_for_status()

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
        response = requests.get(url, params=params)
        data = response.json()

        if data.get("status") != "000":
            if fs_div == "CFS":
                # 연결재무제표가 없는 소형주 → 별도재무제표로 fallback
                return self.get_financial_accounts(corp_code, bsns_year, reprt_code, fs_div="OFS")
            print(f"[WARN] 재무제표 조회 실패 ({bsns_year}/{reprt_code}): {data.get('message')}")
            return None

        accounts = {}
        for item in data.get("list", []):
            sj_div = (item.get("sj_div") or "").strip()
            name = (item.get("account_nm") or "").strip()
            if (sj_div, name) not in self.TARGET_ACCOUNTS:
                continue
            amount_str = (item.get("thstrm_amount") or "").replace(",", "").strip()
            try:
                amount = int(amount_str)
            except (ValueError, TypeError):
                continue
            # IS 우선, IS 없을 때만 CIS로 fallback (이미 IS 값이 있으면 덮어쓰지 않음)
            if sj_div == "CIS" and name in accounts:
                continue
            accounts[name] = amount
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
        response = requests.get(url, params=params)
        data = response.json()

        if data.get("status") != "000":
            print(f"[WARN] 주식수 조회 실패: {data.get('message')}")
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
        response = requests.get(url, params=params)
        data = response.json()

        if data.get("status") not in ("000", "013"):  # 013 = 조회된 데이터 없음
            print(f"[WARN] 공시 조회 실패: {data.get('message')}")
            return []
        return data.get("list", [])


if __name__ == "__main__":
    client = DartApiClient()
    code = client.get_corp_code("005930")
    print(f"삼성전자 corp_code: {code}")
    accounts = client.get_financial_accounts(code, 2024)
    print(json.dumps(accounts, indent=2, ensure_ascii=False))
    shares = client.get_shares_outstanding(code, 2024)
    print(f"발행주식수: {shares:,}")
