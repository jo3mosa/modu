import { useState, useEffect, useRef, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Search, ArrowLeft, TrendingUp, BarChart2 } from 'lucide-react';
import TradingChart from '../components/TradingChart';
import TradingNews from '../components/TradingNews';
import OrderBook from '../components/OrderBook';
import { getStocks, getStockDetail } from '../api/market';
import { buildStockWsUrl } from '../api/wsUrl';
import './TradingPage.css';

/**
 * 백엔드 StockDetailResponse에는 `compareRate`(전일 대비율 %)는 있지만
 * 절대 변화량(`changeAmount`)은 없다. currentPrice와 compareRate로 역산한다.
 */
function computeChangeAmount(currentPrice, compareRate) {
  if (currentPrice == null || compareRate == null) return 0;
  const denom = 100 + compareRate;
  if (denom === 0) return 0;
  return Math.round((currentPrice * compareRate) / denom);
}

// 한글 초성 추출 유틸리티
function getChoseong(str) {
  const cho = ["ㄱ","ㄲ","ㄴ","ㄷ","ㄸ","ㄹ","ㅁ","ㅂ","ㅃ","ㅅ","ㅆ","ㅇ","ㅈ","ㅉ","ㅊ","ㅋ","ㅌ","ㅍ","ㅎ"];
  let result = "";
  for (let i = 0; i < str.length; i++) {
    const code = str.charCodeAt(i) - 44032;
    if (code > -1 && code < 11172) {
      result += cho[Math.floor(code / 588)];
    } else {
      result += str.charAt(i);
    }
  }
  return result;
}

export default function TradingPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const stockCode = searchParams.get('stock'); // null 이면 목록 홈 노출

  // 1. 종목 목록 홈용 상태
  const [stocks, setStocks] = useState([]);
  const [isLoadingStocks, setIsLoadingStocks] = useState(false);
  const wsInitialized = useRef(false);

  // 2. 단일 종목 상세용 상태
  const [stockDetail, setStockDetail] = useState(null);
  const [wsStatus, setWsStatus] = useState('connecting'); // 'connecting' | 'live' | 'disconnected'

  // ── [FLOW A] 종목 목록 홈 데이터 조회 ──
  useEffect(() => {
    if (stockCode) return; // 상세 뷰일 때는 미작동
    
    let cancelled = false;
    async function loadStocksAndDetails() {
      setIsLoadingStocks(true);
      try {
        const data = await getStocks({ page: 1, size: 100 }); // 100개 로드하여 충분한 선택지 확보
        if (cancelled) return;
        const list = data?.stocks ?? [];

        // 각 종목의 초기 REST 시세 정보를 병렬 병합
        const details = await Promise.all(
          list.map(async (s) => {
            try {
              const detail = await getStockDetail(s.stockCode);
              return {
                ...s,
                ...detail,
                flashDirection: null,
                flashTime: 0
              };
            } catch {
              return {
                ...s,
                currentPrice: 0,
                compareRate: 0,
                accumulatedVolume: 0,
                flashDirection: null,
                flashTime: 0
              };
            }
          })
        );

        // 시가총액(marketCap) 기준 내림차순 정렬 (시총이 없는 경우 0으로 강제 처리)
        details.sort((a, b) => {
          const capA = a.marketCap ?? 0;
          const capB = b.marketCap ?? 0;
          return Number(capB - capA);
        });

        if (!cancelled) {
          setStocks(details);
          wsInitialized.current = false; // 웹소켓 초기화 플래그 리셋
        }
      } catch (err) {
        console.error('종목 리스트 로드 실패:', err);
      } finally {
        if (!cancelled) setIsLoadingStocks(false);
      }
    }

    loadStocksAndDetails();
    return () => {
      cancelled = true;
    };
  }, [stockCode]);

  // ── [FLOW A] 목록 홈용 실시간 웹소켓 가격 갱신 풀 연동 ──
  useEffect(() => {
    if (stockCode || stocks.length === 0 || wsInitialized.current) return;
    wsInitialized.current = true;

    const sockets = stocks.map((s) => {
      const ws = new WebSocket(buildStockWsUrl(s.stockCode, 'price'));
      
      ws.onmessage = (event) => {
        try {
          const tick = JSON.parse(event.data);
          setStocks((prev) =>
            prev.map((item) => {
              if (item.stockCode === s.stockCode) {
                const oldPrice = item.currentPrice;
                const newPrice = tick.currentPrice ?? item.currentPrice;
                
                let flash = null;
                if (newPrice > oldPrice && oldPrice > 0) flash = 'up';
                else if (newPrice < oldPrice && oldPrice > 0) flash = 'down';

                return {
                  ...item,
                  currentPrice: newPrice,
                  compareRate: tick.priceChangeRate ?? item.compareRate,
                  accumulatedVolume: tick.accumulatedVolume ?? item.accumulatedVolume,
                  flashDirection: flash,
                  flashTime: flash ? Date.now() : item.flashTime
                };
              }
              return item;
            })
          );
        } catch (e) {
          console.warn('실시간 목록 가격 갱신 실패:', e);
        }
      };
      return ws;
    });

    return () => {
      sockets.forEach((ws) => {
        if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) {
          ws.close();
        }
      });
      wsInitialized.current = false;
    };
  }, [stockCode, stocks.length]);

  // ── [FLOW B] 단일 종목 상세 REST 데이터 조회 ──
  useEffect(() => {
    if (!stockCode) return;
    let cancelled = false;
    async function fetchDetail() {
      try {
        const data = await getStockDetail(stockCode);
        if (!cancelled) setStockDetail(data);
      } catch (error) {
        if (cancelled) return;
        console.error('종목 상세 로드 실패:', error);
        setStockDetail(null);
      }
    }
    fetchDetail();
    return () => {
      cancelled = true;
    };
  }, [stockCode]);

  // ── [FLOW B] 단일 종목 상세 실시간 체결가 WebSocket 연동 ──
  useEffect(() => {
    if (!stockCode) return;

    setWsStatus('connecting');
    const ws = new WebSocket(buildStockWsUrl(stockCode, 'price'));
    let intentionalClose = false;

    ws.onopen = () => {
      setWsStatus('live');
    };

    ws.onmessage = (event) => {
      try {
        const tick = JSON.parse(event.data);
        setStockDetail((prev) =>
          prev
            ? {
                ...prev,
                currentPrice: tick.currentPrice ?? prev.currentPrice,
                compareRate: tick.priceChangeRate ?? prev.compareRate,
                highPrice: tick.highPrice ?? prev.highPrice,
                lowPrice: tick.lowPrice ?? prev.lowPrice,
                accumulatedVolume: tick.accumulatedVolume ?? prev.accumulatedVolume,
              }
            : prev
        );
      } catch (error) {
        console.error('실시간 체결가 메시지 파싱 실패:', error);
      }
    };

    ws.onerror = () => {
      if (!intentionalClose) setWsStatus('disconnected');
    };

    ws.onclose = () => {
      if (!intentionalClose) setWsStatus('disconnected');
    };

    return () => {
      intentionalClose = true;
      if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) {
        ws.close();
      }
    };
  }, [stockCode]);

  // ============================================
  // 1. [RENDER A] 종목 목록 그리드 홈 화면
  // ============================================
  if (!stockCode) {
    return (
      <div className="trading-container">
        <div className="page-header-container">
          <div className="page-title-group">
            <h1>트레이딩 룸</h1>
            <p>실시간 시세를 보며 거래를 시작할 종목을 선택하세요.</p>
          </div>
        </div>

        {/* 로딩 뷰 */}
        {isLoadingStocks && stocks.length === 0 ? (
          <div className="selector-loading-container">
            <div className="selector-spinner" />
            <p>실시간 시세 피드를 준비하고 있습니다…</p>
          </div>
        ) : (
          <>
            {stocks.length === 0 ? (
              <div className="selector-empty-container">
                <p>일치하는 종목이 존재하지 않습니다.</p>
              </div>
            ) : (
              <div className="stock-selector-grid">
                {stocks.map((s) => {
                  const isUp = s.compareRate >= 0;
                  const changeColor = isUp ? 'up' : 'down';
                  const changeAmount = computeChangeAmount(s.currentPrice, s.compareRate);

                  // 실시간 플래시 효과 지속 여부 점검 (1초 이내)
                  const hasFlash = s.flashDirection && (Date.now() - s.flashTime < 1000);
                  const flashClass = hasFlash ? `flash-${s.flashDirection}` : '';

                  return (
                    <article
                      key={s.stockCode}
                      className="stock-summary-card"
                      onClick={() => setSearchParams({ stock: s.stockCode })}
                    >
                      <header className="card-header">
                        <div className="card-identity">
                          <h3 className="card-name">{s.stockName}</h3>
                          <span className="card-code">{s.stockCode}</span>
                        </div>
                        <span className={`card-market-badge ${s.marketType?.toLowerCase()}`}>
                          {s.marketType ?? 'KOSPI'}
                        </span>
                      </header>

                      <div className="card-body">
                        <div className={`card-price ${flashClass}`}>
                          {s.currentPrice > 0 ? `${s.currentPrice.toLocaleString()}원` : '-'}
                        </div>
                        <div className={`card-change ${changeColor}`}>
                          {isUp ? '+' : ''}{changeAmount.toLocaleString()}원
                          &nbsp;({isUp ? '+' : ''}{s.compareRate.toFixed(2)}%)
                        </div>
                      </div>

                      <footer className="card-footer">
                        <div className="card-volume">
                          <span>거래량</span>
                          <strong>{s.accumulatedVolume?.toLocaleString() ?? '0'} 주</strong>
                        </div>
                        <div className="card-action-btn">
                          <TrendingUp size={16} /> 거래하기
                        </div>
                      </footer>
                    </article>
                  );
                })}
              </div>
            )}
          </>
        )}
      </div>
    );
  }

  // ============================================
  // 2. [RENDER B] 단일 종목 상세 거래 화면
  // ============================================
  if (!stockDetail) {
    return (
      <div className="trading-container">
        <div className="selector-loading-container">
          <div className="selector-spinner" />
          <p>종목 상세 데이터를 불러오고 있습니다…</p>
        </div>
      </div>
    );
  }

  const compareRate = stockDetail.compareRate ?? 0;
  const isUp = compareRate >= 0;
  const changeColor = isUp ? 'up' : 'down';
  const changeAmount = computeChangeAmount(stockDetail.currentPrice, compareRate);

  return (
    <div className="trading-container">
      <div className="page-header-container">
        <div className="page-title-group">
          <h1>트레이딩 룸</h1>
          <p>실시간 호가와 차트를 보며 매매를 진행하세요.</p>
        </div>
      </div>

      {/* 선택 종목 정보 스트립 */}
      <div className="stock-info-strip">
        <div className="stock-identity">
          <button
            type="button"
            className="back-to-list-btn"
            onClick={() => setSearchParams({})}
            title="종목 목록 홈으로 가기"
          >
            <ArrowLeft size={16} />
            목록으로
          </button>
          <span className="stock-strip-name">{stockDetail.stockName}</span>
          <span className="stock-strip-code">{stockCode}</span>
          
          <span
            className={`ws-status-badge ws-status-${wsStatus}`}
            title={
              wsStatus === 'live' ? '실시간 가격이 정상 수신되고 있어요.'
              : wsStatus === 'connecting' ? '실시간 가격 채널에 연결 중입니다…'
              : '실시간 가격 채널이 끊겼습니다. 가격이 갱신되지 않을 수 있어요.'
            }
          >
            <span className="ws-status-dot" />
            {wsStatus === 'live' && '실시간'}
            {wsStatus === 'connecting' && '연결 중'}
            {wsStatus === 'disconnected' && '연결 끊김'}
          </span>
        </div>
        <div className="stock-price-group">
          <span className="stock-strip-price">{stockDetail.currentPrice.toLocaleString()}원</span>
          <span className={`stock-strip-change ${changeColor}`}>
            {isUp ? '+' : ''}{changeAmount.toLocaleString()}원
            &nbsp;({isUp ? '+' : ''}{compareRate.toFixed(2)}%)
          </span>
        </div>
        <div className="stock-meta-group">
          <span className="meta-item"><em>거래량</em>{stockDetail.accumulatedVolume?.toLocaleString() ?? '-'}</span>
          <span className="meta-item"><em>고</em><span className="up">{stockDetail.highPrice?.toLocaleString() ?? '-'}</span></span>
          <span className="meta-item"><em>저</em><span className="down">{stockDetail.lowPrice?.toLocaleString() ?? '-'}</span></span>
        </div>
      </div>

      <div className="trading-top">
        <div className="chart-section">
          <TradingChart stockCode={stockCode} />
        </div>
        <div className="order-section">
          <OrderBook stockCode={stockCode} />
        </div>
      </div>

      <div className="news-section">
        <TradingNews stockCode={stockCode} />
      </div>
    </div>
  );
}
