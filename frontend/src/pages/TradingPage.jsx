import { useState, useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import TradingChart from '../components/TradingChart';
import TradingNews from '../components/TradingNews';
import OrderBook from '../components/OrderBook';
// import { getStockDetail } from '../api/market';
import './TradingPage.css';

// ── MOCK 종목 상세 데이터 (백엔드 연동 후 삭제 예정) ──────────────────────────
const MOCK_STOCK_DETAILS = {
  '005930': { stockName: '삼성전자',          currentPrice: 74900,  changeAmount:   300, changeRate:  0.40, volume: 12345678, high: 75400, low: 74200 },
  '000660': { stockName: 'SK하이닉스',        currentPrice: 197500, changeAmount: -2500, changeRate: -1.25, volume:  8234567, high: 201000, low: 196000 },
  '035420': { stockName: 'NAVER',             currentPrice: 184500, changeAmount:  1500, changeRate:  0.82, volume:  1023456, high: 186000, low: 183000 },
  '035720': { stockName: '카카오',            currentPrice: 45000,  changeAmount: -1000, changeRate: -2.17, volume:  5678901, high: 46200,  low: 44800  },
  '012450': { stockName: '한화에어로스페이스', currentPrice: 85300,  changeAmount:  2800, changeRate:  3.45, volume:  2345678, high: 86000,  low: 83000  },
  '005380': { stockName: '현대차',            currentPrice: 213500, changeAmount:  2500, changeRate:  1.17, volume:  3456789, high: 215000, low: 211000 },
  '000270': { stockName: '기아',              currentPrice: 98200,  changeAmount:   600, changeRate:  0.62, volume:  4567890, high: 99000,  low: 97500  },
  '068270': { stockName: '셀트리온',          currentPrice: 178000, changeAmount: -1000, changeRate: -0.56, volume:  1234567, high: 180000, low: 177000 },
};
const DEFAULT_STOCK_CODE = '005930';
// ─────────────────────────────────────────────────────────────────────────────

export default function TradingPage() {
  const [searchParams] = useSearchParams();
  const stockCode = searchParams.get('stock') ?? DEFAULT_STOCK_CODE;

  const [stockDetail, setStockDetail] = useState(null);

  // 종목 변경 시 상세 데이터 로드
  // ── 연동 시 아래 MOCK 블록 → 주석 처리하고 아래 블록 해제 ─────────────────
  // useEffect(() => {
  //   async function fetchDetail() {
  //     try {
  //       const data = await getStockDetail(stockCode); // GET /api/v1/markets/stocks/{stockCode}
  //       setStockDetail(data);
  //     } catch (error) {
  //       console.error('종목 상세 로드 실패:', error);
  //     }
  //   }
  //   fetchDetail();
  // }, [stockCode]);
  // ─────────────────────────────────────────────────────────────────────────
  useEffect(() => {
    const detail = MOCK_STOCK_DETAILS[stockCode] ?? MOCK_STOCK_DETAILS[DEFAULT_STOCK_CODE];
    setStockDetail(detail);
  }, [stockCode]);

  if (!stockDetail) return null;

  const isUp = stockDetail.changeRate >= 0;
  const changeColor = isUp ? 'up' : 'down';

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
            {isUp ? '+' : ''}{stockDetail.changeAmount.toLocaleString()}원
            &nbsp;({isUp ? '+' : ''}{stockDetail.changeRate}%)
          </span>
        </div>
        <div className="stock-meta-group">
          <span className="meta-item"><em>거래량</em>{stockDetail.volume.toLocaleString()}</span>
          <span className="meta-item"><em>고</em><span className="up">{stockDetail.high.toLocaleString()}</span></span>
          <span className="meta-item"><em>저</em><span className="down">{stockDetail.low.toLocaleString()}</span></span>
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
