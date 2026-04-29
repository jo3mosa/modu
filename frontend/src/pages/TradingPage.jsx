import TradingChart from '../components/TradingChart';
import TradingNews from '../components/TradingNews';
import OrderBook from '../components/OrderBook';
import './TradingPage.css';

export default function TradingPage() {
  return (
    <div className="trading-container">
      <div className="trading-header">
        <h1>트레이딩 룸</h1>
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
