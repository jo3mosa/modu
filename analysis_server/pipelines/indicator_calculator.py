import sqlite3
import pandas as pd
import json
from ta.trend import SMAIndicator, MACD
from ta.momentum import RSIIndicator
from ta.volatility import BollingerBands, AverageTrueRange
from ta.volume import MFIIndicator
from kis_api_client import KisApiClient 

def calculate_indicators(stock_code, db_path="../data/stock_master.db"):
    # 1. 과거 데이터 불러오기 (고가, 저가, 거래량 추가)
    conn = sqlite3.connect(db_path)
    query = f"""
        SELECT date, open, high, low, close, volume 
        FROM daily_ohlcv 
        WHERE stock_code = '{stock_code}' 
        ORDER BY date DESC 
        LIMIT 100
    """
    df = pd.read_sql(query, conn)
    conn.close()
    
    df = df.sort_values('date').reset_index(drop=True)
    
    # 2. KIS API 실시간 스냅샷 병합 
    kis = KisApiClient()
    snapshot = kis.get_realtime_snapshot(stock_code)
    
    if snapshot:
        df = df[df['date'] != snapshot['date']]
        today_df = pd.DataFrame([{
            'date': snapshot['date'],
            'open': snapshot.get('open', df['close'].iloc[-1]),
            'high': snapshot.get('high', snapshot['close']),
            'low': snapshot.get('low', snapshot['close']),
            'close': snapshot['close'],
            'volume': snapshot.get('volume', 0)
        }])
        df = pd.concat([df, today_df], ignore_index=True)
    
    # 숫자형 강제 변환
    for col in ['open', 'high', 'low', 'close', 'volume']:
        df[col] = pd.to_numeric(df[col], errors='coerce')
    
    # --- 3. 보조 지표 연산 (ta 라이브러리) ---
    # 추세 (SMA)
    df['sma_5'] = SMAIndicator(close=df['close'], window=5).sma_indicator()
    df['sma_20'] = SMAIndicator(close=df['close'], window=20).sma_indicator()
    df['sma_60'] = SMAIndicator(close=df['close'], window=60).sma_indicator()
    
    # 추세 (MACD)
    macd = MACD(close=df['close'], window_slow=26, window_fast=12, window_sign=9)
    df['macd'] = macd.macd()
    df['macd_signal'] = macd.macd_signal()

    # 모멘텀 (RSI)
    df['rsi_14'] = RSIIndicator(close=df['close'], window=14).rsi()
    
    # 변동성 (Bollinger Bands, ATR)
    bb = BollingerBands(close=df['close'], window=20, window_dev=2)
    df['bb_upper'] = bb.bollinger_hband()
    df['bb_lower'] = bb.bollinger_lband()
    
    atr = AverageTrueRange(high=df['high'], low=df['low'], close=df['close'], window=14)
    df['atr'] = atr.average_true_range()
    
    # 거래량 (MFI)
    mfi = MFIIndicator(high=df['high'], low=df['low'], close=df['close'], volume=df['volume'], window=14)
    df['mfi_14'] = mfi.money_flow_index()

    # --- 4. AI 분석용 상태(State) 추론 로직 ---
    if len(df) < 2:
        return {"error": "데이터가 부족하여 지표 상태를 계산할 수 없습니다."}

    latest = df.iloc[-1]
    prev = df.iloc[-2] # MACD 크로스 비교를 위한 전일 데이터

    # A. 추세 지표 (Trend)
    # 1) SMA Alignment
    if pd.notna(latest['sma_60']):
        if latest['sma_5'] > latest['sma_20'] > latest['sma_60']:
            sma_alignment = "bullish_aligned"
        elif latest['sma_5'] < latest['sma_20'] < latest['sma_60']:
            sma_alignment = "bearish_aligned"
        else:
            sma_alignment = "mixed"
    else:
        sma_alignment = "mixed"

    # 2) MACD State
    macd_curr, sig_curr = latest['macd'], latest['macd_signal']
    macd_prev, sig_prev = prev['macd'], prev['macd_signal']
    
    if pd.notna(macd_curr) and pd.notna(sig_curr) and pd.notna(macd_prev) and pd.notna(sig_prev):
        if macd_prev <= sig_prev and macd_curr > sig_curr:
            macd_state = "bullish_cross"
        elif macd_prev >= sig_prev and macd_curr < sig_curr:
            macd_state = "bearish_cross"
        elif macd_curr > sig_curr:
            macd_state = "uptrend"
        else:
            macd_state = "downtrend"
    else:
        macd_state = "mixed"

    # B. 변동성 지표 (Volatility)
    # 1) Bollinger Position
    close_price = latest['close']
    if pd.notna(latest['bb_upper']) and close_price > latest['bb_upper']:
        bollinger_position = "upper_breakout"
    elif pd.notna(latest['bb_lower']) and close_price < latest['bb_lower']:
        bollinger_position = "lower_breakout"
    else:
        bollinger_position = "inside_band"

    # 2) ATR Ratio (현재 주가 대비 ATR 비율)
    atr_val = latest['atr']
    atr_ratio = round(atr_val / close_price, 3) if pd.notna(atr_val) and close_price > 0 else 0.0

    # --- 5. 최종 JSON 결과 포맷팅 ---
    result = {
        "stock_code": stock_code,
        "date": latest['date'],
        "current_price": int(close_price),
        "technical": {
            "trend": {
                "sma_alignment": sma_alignment,
                "macd_state": macd_state
            },
            "momentum": {
                "rsi_14": round(latest['rsi_14'], 2) if pd.notna(latest['rsi_14']) else None
            },
            "volatility": {
                "bollinger_position": bollinger_position,
                "atr_ratio": atr_ratio
            },
            "volume": {
                "mfi_14": round(latest['mfi_14'], 2) if pd.notna(latest['mfi_14']) else None
            }
        }
    }
    
    return result

# if __name__ == "__main__":
#     # 데이터가 정상 적재된 종목(예: 060310)으로 테스트 진행
#     test_code = "054620" 
#     print(f"[{test_code}] AI 분석용 기술적 지표 추론 및 JSON 포맷팅 중\n")
    
#     result_data = calculate_indicators(test_code)
#     print(json.dumps(result_data, indent=2, ensure_ascii=False))