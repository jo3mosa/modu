import './OrderBook.css';

const MOCK_ASKS = [
  { price: '75,400', amount: '12,450' },
  { price: '75,300', amount: '34,500' },
  { price: '75,200', amount: '82,000' },
  { price: '75,100', amount: '154,000' },
  { price: '75,000', amount: '221,000' },
];

const MOCK_BIDS = [
  { price: '74,900', amount: '121,000' },
  { price: '74,800', amount: '53,000' },
  { price: '74,700', amount: '32,000' },
  { price: '74,600', amount: '11,000' },
  { price: '74,500', amount: '8,500' },
];

export default function OrderBook() {
  // [WebSocket 실시간 호가 연동 지점]
  /*
  const [asks, setAsks] = useState(MOCK_ASKS);
  const [bids, setBids] = useState(MOCK_BIDS);
  const [currentPrice, setCurrentPrice] = useState('74,900');

  useEffect(() => {
    const ws = new WebSocket('ws://api.modu.com/ws/stocks/005930/orderbook');
    
    ws.onmessage = (event) => {
      const data = JSON.parse(event.data);
      // data: { asks: [...], bids: [...], currentPrice: '75,000' }
      setAsks(data.asks);
      setBids(data.bids);
      setCurrentPrice(data.currentPrice);
    };

    return () => ws.close();
  }, []);
  */

  // [REST API 수동 주문 연동 지점]
  /*
  const handleOrder = async (orderType) => {
    try {
      const response = await fetch('/api/v1/orders', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          stockCode: '005930',
          orderType: orderType, // 'BUY' or 'SELL'
          price: 74900,
          quantity: 1
        })
      });
      if(response.ok) alert('주문이 접수되었습니다.');
    } catch (e) {
      console.error("주문 실패:", e);
    }
  };
  */

  return (
    <div className="orderbook-wrapper">
      <div className="orderbook-header">
        <h3>호가/주문 탭</h3>
      </div>
      
      <div className="orderbook-table">
        <div className="table-head">
          <span>가격(원)</span>
          <span>수량</span>
        </div>
        
        <div className="asks-list">
          {MOCK_ASKS.slice().reverse().map((ask, idx) => (
            <div key={idx} className="order-row ask">
              <span className="col-price">{ask.price}</span>
              <span className="col-amount">{ask.amount}</span>
              {/* 바 배경 효과: 최대 25000 기준 퍼센트 */}
              <div className="bg-bar" style={{ width: `${Math.min((parseInt(ask.amount.replace(/,/g,'')) / 25000) * 100, 100)}%` }} />
            </div>
          ))}
        </div>
        
        <div className="current-price-divider">
          <span className="current-price">74,900</span>
          <span className="price-diff">-300 (-0.40%)</span>
        </div>
        
        <div className="bids-list">
          {MOCK_BIDS.map((bid, idx) => (
            <div key={idx} className="order-row bid">
              <span className="col-price">{bid.price}</span>
              <span className="col-amount">{bid.amount}</span>
              <div className="bg-bar" style={{ width: `${Math.min((parseInt(bid.amount.replace(/,/g,'')) / 25000) * 100, 100)}%` }} />
            </div>
          ))}
        </div>

        <div className="order-actions">
          <button className="buy-btn" /* onClick={() => handleOrder('BUY')} */>매수</button>
          <button className="sell-btn" /* onClick={() => handleOrder('SELL')} */>매도</button>
        </div>
      </div>
    </div>
  );
}
