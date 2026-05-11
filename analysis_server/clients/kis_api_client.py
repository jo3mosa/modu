import os
import requests
import json
import threading
from datetime import datetime, timedelta
from dotenv import load_dotenv

# .env 파일 로드
load_dotenv()

_MODULE_DIR = os.path.dirname(os.path.abspath(__file__))

# 토큰 만료 직전 호출 시 race 를 피하기 위한 안전 margin.
# 만료 5분 전부터는 만료된 것으로 간주하여 재발급.
_TOKEN_REFRESH_MARGIN = timedelta(minutes=5)


class KisApiClient:
    def __init__(self):
        self.app_key = os.getenv("KIS_APP_KEY")
        self.app_secret = os.getenv("KIS_APP_SECRET")
        self.base_url = os.getenv("KIS_BASE_URL")
        # CWD에 무관하게 clients/ 옆에 토큰 캐시 — gitignored
        self.token_file = os.path.join(_MODULE_DIR, "kis_token.json")
        # 토큰 메모리 캐시 — fast path 에서 lock·파일 I/O 회피.
        # 여러 스레드 동시 발급 방지를 위한 lock.
        self._token_cache: tuple[str, datetime] | None = None
        self._token_lock = threading.Lock()

    def _issue_token(self):
        """접근 토큰을 발급받고 파일·메모리 캐시에 저장합니다. 호출자는 _token_lock 보유 가정."""
        url = f"{self.base_url}/oauth2/tokenP"
        headers = {"content-type": "application/json"}
        body = {
            "grant_type": "client_credentials",
            "appkey": self.app_key,
            "appsecret": self.app_secret
        }

        response = requests.post(url, headers=headers, data=json.dumps(body))
        res_data = response.json()

        if response.status_code == 200:
            access_token = res_data["access_token"]
            expired_at = datetime.now() + timedelta(hours=23)

            with open(self.token_file, "w") as f:
                json.dump(
                    {"access_token": access_token, "expired_at": expired_at.isoformat()},
                    f,
                )

            self._token_cache = (access_token, expired_at)
            print("[SUCCESS] KIS API 토큰 신규 발급 및 파일 저장 완료")
            return access_token
        else:
            raise Exception(f"토큰 발급 실패: {res_data}")

    def _get_valid_token(self):
        """thread-safe 토큰 조회.

        Fast path: 메모리 캐시 유효 → lock 없이 즉시 반환.
        Slow path: lock 잡고 (double-checked) 파일 시도 → 그래도 없으면 새 발급.
        """
        now = datetime.now()

        # Fast path — 메모리 캐시 적중 (lock 무관, 동시 read 안전)
        cache = self._token_cache
        if cache and now < cache[1] - _TOKEN_REFRESH_MARGIN:
            return cache[0]

        # Slow path — 발급 또는 파일 로드. 여러 스레드 중복 발급 방지.
        with self._token_lock:
            # Lock 안에서 다시 확인 (다른 스레드가 방금 발급했을 수 있음)
            cache = self._token_cache
            if cache and now < cache[1] - _TOKEN_REFRESH_MARGIN:
                return cache[0]

            # 파일 캐시 시도
            if os.path.exists(self.token_file):
                try:
                    with open(self.token_file, "r") as f:
                        data = json.load(f)
                    expired_at = datetime.fromisoformat(data["expired_at"])
                    if now < expired_at - _TOKEN_REFRESH_MARGIN:
                        self._token_cache = (data["access_token"], expired_at)
                        return data["access_token"]
                except (json.JSONDecodeError, KeyError, ValueError):
                    pass  # 손상된 캐시 — 새 발급으로 fallthrough

            # 파일 없거나 만료 — 새 발급
            return self._issue_token()

    def get_realtime_snapshot(self, stock_code):
        """특정 종목의 실시간 5대 지표 스냅샷을 가져옵니다."""
        token = self._get_valid_token()
        url = f"{self.base_url}/uapi/domestic-stock/v1/quotations/inquire-price"
        
        headers = {
            "content-type": "application/json; charset=utf-8",
            "authorization": f"Bearer {token}",
            "appkey": self.app_key,
            "appsecret": self.app_secret,
            "tr_id": "FHKST01010100"
        }
        
        params = {
            "fid_cond_mrkt_div_code": "J",
            "fid_input_iscd": stock_code
        }
        
        response = requests.get(url, headers=headers, params=params)
        res_data = response.json()
        
        if res_data["rt_cd"] == "0":
            output = res_data["output"]
            snapshot = {
                "stock_code": stock_code,
                "date": datetime.today().strftime('%Y-%m-%d'),
                "open": int(output["stck_oprc"]),
                "high": int(output["stck_hgpr"]),
                "low": int(output["stck_lwpr"]),
                "close": int(output["stck_prpr"]),
                "volume": int(output["acml_vol"])
            }
            return snapshot
        else:
            print(f"[FAIL] 데이터 조회 실패 [{stock_code}]: {res_data['msg1']}")
            return None

if __name__ == "__main__":
    client = KisApiClient()
    snapshot = client.get_realtime_snapshot("005930")
    print(f"[실시간 스냅샷 결과]\n{json.dumps(snapshot, indent=2, ensure_ascii=False)}")