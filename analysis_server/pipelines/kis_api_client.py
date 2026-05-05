import os
import requests
import json
from datetime import datetime, timedelta
from dotenv import load_dotenv

# .env 파일 로드
load_dotenv()

class KisApiClient:
    def __init__(self):
        self.app_key = os.getenv("KIS_APP_KEY")
        self.app_secret = os.getenv("KIS_APP_SECRET")
        self.base_url = os.getenv("KIS_BASE_URL")
        self.token_file = "kis_token.json"  # 토큰을 저장할 파일 이름

    def _issue_token(self):
        """접근 토큰을 발급받고 파일에 저장합니다."""
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
            # 토큰 유효기간 (23시간 뒤)
            expired_at = (datetime.now() + timedelta(hours=23)).isoformat()
            
            # ✨ 메모리가 아니라 파일에 도장 쾅!
            with open(self.token_file, "w") as f:
                json.dump({"access_token": access_token, "expired_at": expired_at}, f)
                
            print("[SUCCESS] KIS API 토큰 신규 발급 및 파일 저장 완료")
            return access_token
        else:
            raise Exception(f"토큰 발급 실패: {res_data}")

    def _get_valid_token(self):
        """파일에서 토큰을 읽어오고, 없거나 만료되었으면 새로 발급합니다."""
        if os.path.exists(self.token_file):
            with open(self.token_file, "r") as f:
                data = json.load(f)
                expired_at = datetime.fromisoformat(data["expired_at"])
                
                # 아직 유효기간이 안 지났다면 파일에 있는 토큰 재사용!
                if datetime.now() < expired_at:
                    return data["access_token"]
                    
        # 파일이 없거나 유효기간이 지났으면 새로 발급
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