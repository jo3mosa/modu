import TradingChart from '../components/TradingChart';
import TradingNews from '../components/TradingNews';
import OrderBook from '../components/OrderBook';
import './TradingPage.css';

export default function TradingPage() {
  return (
    <div className="trading-container">
      <div className="page-header-container">
        <div className="page-title-group">
          <h1>트레이딩 룸</h1>
          <p>실시간 호가와 차트를 보며 매매를 진행하세요.</p>
        </div>
      </div>
      
      <div className="trading-top">
        <div className="chart-section">
          <TradingChart />
        </div>
        <div className="order-section">
          <OrderBook />
        </div>
      </div>

      <div className="news-section">
        <TradingNews />
      </div>
    </div>
  );
}
