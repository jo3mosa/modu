import { useState, useEffect } from 'react';
// import { getBuyingPower, placeOrder, getPendingOrders, updateOrder } from '../api/order';
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

export default function OrderBook({ stockCode }) {
  const [activeTab, setActiveTab] = useState('ORDER'); // 'ORDER' | 'PENDING'
  const [orderType, setOrderType] = useState('BUY'); // 'BUY' | 'SELL'
  
  const [price, setPrice] = useState(74900);
  const [quantity, setQuantity] = useState(1);
  const [buyingPower, setBuyingPower] = useState({ availableCash: 1500000, maxQuantity: 20 });
  const [pendingOrders, setPendingOrders] = useState([
    { orderId: '1', orderType: 'BUY', price: 74000, quantity: 10, orderedAt: '10:30:00' },
    { orderId: '2', orderType: 'SELL', price: 76000, quantity: 5, orderedAt: '11:15:22' }
  ]);

  // TODO: 연동 시 주석 해제 (호가/현재가 WebSocket 연결)
  // const [asks, setAsks] = useState(MOCK_ASKS);
  // const [bids, setBids] = useState(MOCK_BIDS);
  // const [currentPrice, setCurrentPrice] = useState('74,900');
  // useEffect(() => {
  //   const ws = new WebSocket(`/ws/stocks/${stockCode}/orderbook`);
  //   ws.onmessage = (event) => {
  //     const data = JSON.parse(event.data);
  //     setAsks(data.asks);
  //     setBids(data.bids);
  //     setCurrentPrice(data.currentPrice);
  //   };
  //   return () => ws.close();
  // }, []);

  // TODO: 연동 시 주석 해제 (주문 가능 금액 모의 조회)
  // useEffect(() => {
  //   async function fetchBuyingPower() {
  //     if (price > 0) {
  //       try {
  //         const res = await getBuyingPower(stockCode, price);
  //         setBuyingPower(res);
  //       } catch (e) {
  //         console.error('주문 가능 금액 조회 실패', e);
  //       }
  //     }
  //   }
  //   fetchBuyingPower();
  // }, [stockCode, price]);

  // TODO: 연동 시 주석 해제 (미체결 주문 조회)
  // useEffect(() => {
  //   if (activeTab === 'PENDING') {
  //     async function fetchPending() {
  //       try {
  //         const res = await getPendingOrders();
  //         setPendingOrders(res.filter(o => o.stockCode === stockCode));
  //       } catch (e) {
  //         console.error('미체결 내역 조회 실패', e);
  //       }
  //     }
  //     fetchPending();
  //   }
  // }, [activeTab, stockCode]);

  const handleOrder = async () => {
    alert(`${orderType === 'BUY' ? '매수' : '매도'} 주문이 접수되었습니다.\n(단가: ${price}원, 수량: ${quantity}주)`);
    // TODO: 연동 시 실제 호출
    // try {
    //   await placeOrder({ stockCode, orderType, price, quantity });
    //   alert('주문이 완료되었습니다.');
    // } catch (e) {
    //   console.error("주문 실패:", e);
    // }
  };

  const handleCancelOrder = async (orderId) => {
    alert(`주문(${orderId})이 취소되었습니다.`);
    setPendingOrders(prev => prev.filter(o => o.orderId !== orderId));
    // TODO: 연동 시 실제 호출
    // try {
    //   await updateOrder(orderId, { cancel: true });
    // } catch (e) {
    //   console.error("주문 취소 실패:", e);
    // }
  };

  return (
    <div className="orderbook-wrapper">
      <div className="orderbook-tabs">
        <button 
          className={`tab-btn ${activeTab === 'ORDER' ? 'active' : ''}`} 
          onClick={() => setActiveTab('ORDER')}
        >
          호가·주문
        </button>
        <button 
          className={`tab-btn ${activeTab === 'PENDING' ? 'active' : ''}`} 
          onClick={() => setActiveTab('PENDING')}
        >
          미체결 내역
        </button>
      </div>

      {activeTab === 'ORDER' && (
        <div className="orderbook-content">
          {/* 상단 호가창 */}
          <div className="orderbook-table">
            <div className="table-head">
              <span>가격(원)</span>
              <span>수량</span>
            </div>

            <div className="asks-list">
              {MOCK_ASKS.slice().reverse().map((ask, idx) => (
                <div key={idx} className="order-row ask" onClick={() => setPrice(parseInt(ask.price.replace(/,/g, '')))}>
                  <span className="col-price">{ask.price}</span>
                  <span className="col-amount">{ask.amount}</span>
                  <div className="bg-bar" style={{ width: `${Math.min((parseInt(ask.amount.replace(/,/g, '')) / 25000) * 100, 100)}%` }} />
                </div>
              ))}
            </div>

            <div className="current-price-divider">
              <span className="current-price">74,900</span>
              <span className="price-diff">-300 (-0.40%)</span>
            </div>

            <div className="bids-list">
              {MOCK_BIDS.map((bid, idx) => (
                <div key={idx} className="order-row bid" onClick={() => setPrice(parseInt(bid.price.replace(/,/g, '')))}>
                  <span className="col-price">{bid.price}</span>
                  <span className="col-amount">{bid.amount}</span>
                  <div className="bg-bar" style={{ width: `${Math.min((parseInt(bid.amount.replace(/,/g, '')) / 25000) * 100, 100)}%` }} />
                </div>
              ))}
            </div>
          </div>

          {/* 하단 주문 폼 */}
          <div className="order-form">
            <div className="order-type-switch">
              <button className={`type-btn buy ${orderType === 'BUY' ? 'active' : ''}`} onClick={() => setOrderType('BUY')}>매수</button>
              <button className={`type-btn sell ${orderType === 'SELL' ? 'active' : ''}`} onClick={() => setOrderType('SELL')}>매도</button>
            </div>
            
            <div className="buying-power-info">
              <span>{orderType === 'BUY' ? '주문 가능 금액' : '매도 가능 수량'}</span>
              <strong>{orderType === 'BUY' ? `${buyingPower.availableCash.toLocaleString()}원` : `${buyingPower.maxQuantity}주`}</strong>
            </div>

            <div className="input-group">
              <label>단가 (원)</label>
              <input type="number" value={price} onChange={(e) => setPrice(Number(e.target.value))} />
            </div>
            <div className="input-group">
              <label>수량 (주)</label>
              <input type="number" value={quantity} onChange={(e) => setQuantity(Number(e.target.value))} />
            </div>
            
            <div className="order-total">
              <span>총 주문 금액</span>
              <strong>{(price * quantity).toLocaleString()}원</strong>
            </div>

            <button className={`submit-order-btn ${orderType.toLowerCase()}`} onClick={handleOrder}>
              {orderType === 'BUY' ? '매수 주문' : '매도 주문'}
            </button>
          </div>
        </div>
      )}

      {activeTab === 'PENDING' && (
        <div className="pending-content">
          {pendingOrders.length === 0 ? (
            <div className="empty-state">미체결 내역이 없습니다.</div>
          ) : (
            <div className="pending-list">
              {pendingOrders.map(order => (
                <div key={order.orderId} className="pending-item">
                  <div className="pending-info">
                    <span className={`pending-type ${order.orderType.toLowerCase()}`}>
                      {order.orderType === 'BUY' ? '매수' : '매도'}
                    </span>
                    <div className="pending-details">
                      <strong>{order.price.toLocaleString()}원</strong>
                      <span>{order.quantity}주</span>
                      <span className="pending-time">{order.orderedAt}</span>
                    </div>
                  </div>
                  <div className="pending-actions">
                    <button className="cancel-btn" onClick={() => handleCancelOrder(order.orderId)}>취소</button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
