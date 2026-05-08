import { useState, useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import TradingChart from '../components/TradingChart';
import TradingNews from '../components/TradingNews';
import OrderBook from '../components/OrderBook';
import { getStockDetail } from '../api/market';
import './TradingPage.css';

const DEFAULT_STOCK_CODE = '005930';

/**
 * 백엔드 StockDetailResponse에는 `compareRate`(전일 대비율 %)는 있지만
 * 절대 변화량(`changeAmount`)은 없다. currentPrice와 compareRate로 역산한다.
 *
 * 어제 종가 = currentPrice / (1 + compareRate/100)
 * 변화량  = currentPrice - 어제 종가 = currentPrice * compareRate / (100 + compareRate)
 */
function computeChangeAmount(currentPrice, compareRate) {
  if (currentPrice == null || compareRate == null) return 0;
  const denom = 100 + compareRate;
  if (denom === 0) return 0;
  return Math.round((currentPrice * compareRate) / denom);
}

export default function TradingPage() {
  const [searchParams] = useSearchParams();
  const stockCode = searchParams.get('stock') ?? DEFAULT_STOCK_CODE;

  const [stockDetail, setStockDetail] = useState(null);

  // 종목 변경 시 상세 데이터 로드
  useEffect(() => {
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

  if (!stockDetail) return null;

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
          <span className="stock-strip-name">{stockDetail.stockName}</span>
          <span className="stock-strip-code">{stockCode}</span>
        </div>
        <div className="stock-price-group">
          <span className="stock-strip-price">{stockDetail.currentPrice.toLocaleString()}원</span>
          <span className={`stock-strip-change ${changeColor}`}>
            {isUp ? '+' : ''}{changeAmount.toLocaleString()}원
            &nbsp;({isUp ? '+' : ''}{compareRate}%)
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
