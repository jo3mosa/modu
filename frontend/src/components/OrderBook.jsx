import { useState, useEffect, useMemo } from 'react';
// 주문/미체결 API는 import 보류
// import { getBuyingPower, placeOrder, getPendingOrders, updateOrder } from '../api/order';
import './OrderBook.css';

export default function OrderBook({ stockCode }) {
  const [orderType, setOrderType] = useState('BUY'); // 'BUY' | 'SELL'

  const [price, setPrice] = useState(0);
  const [quantity, setQuantity] = useState(1);

  // 실시간 호가  asks/bids는 OrderbookLevel[] = { level, price, quantity }
  const [asks, setAsks] = useState([]);
  const [bids, setBids] = useState([]);

  // 호가 WebSocket 연결: /ws/stocks/{code}/orderbook
  useEffect(() => {
    if (!stockCode) return;

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const ws = new WebSocket(`${protocol}//${window.location.host}/ws/stocks/${stockCode}/orderbook`);

    ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        // 매도 호가는 가격 오름차순(level 1: 최우선 매도) → 화면 표시는 reverse로 처리
        setAsks(Array.isArray(data?.asks) ? data.asks : []);
        setBids(Array.isArray(data?.bids) ? data.bids : []);
      } catch (error) {
        console.error('실시간 호가 메시지 파싱 실패:', error);
      }
    };

    ws.onerror = (event) => {
      console.warn('실시간 호가 WebSocket 오류:', event);
    };

    return () => {
      if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) {
        ws.close();
      }
    };
  }, [stockCode]);

  // 화면 표시는 매도 상단/매수 하단 순. 막대 크기 정규화용 최대 잔량
  const maxQuantity = useMemo(() => {
    const allQuantities = [...asks, ...bids].map((lv) => lv?.quantity ?? 0);
    return Math.max(1, ...allQuantities);
  }, [asks, bids]);

  const handleSelectPrice = (lvPrice) => {
    if (typeof lvPrice === 'number') setPrice(lvPrice);
  };

  // 매수/매도 주문 — 백엔드 Order API 미구현 상태라 임시 alert만 표시
  const handleOrder = () => {
    alert(`${orderType === 'BUY' ? '매수' : '매도'} 주문 폼은 백엔드 주문 API 머지 후 활성화됩니다.`);
  };

  return (
    <div className="orderbook-wrapper">
      {/* 호가·주문 영역 */}
      <div className="orderbook-content">
        <div className="orderbook-table">
          <div className="table-head">
            <span>가격(원)</span>
            <span>수량</span>
          </div>

          <div className="asks-list">
            {asks.length === 0 ? (
              <div className="order-row" style={{ color: '#666', justifyContent: 'center' }}>
                매도 호가 수신 대기 중…
              </div>
            ) : (
              asks.slice().reverse().map((ask) => (
                <div
                  key={`ask-${ask.level}`}
                  className="order-row ask"
                  onClick={() => handleSelectPrice(ask.price)}
                >
                  <span className="col-price">{ask.price?.toLocaleString() ?? '-'}</span>
                  <span className="col-amount">{ask.quantity?.toLocaleString() ?? '-'}</span>
                  <div
                    className="bg-bar"
                    style={{ width: `${Math.min(((ask.quantity ?? 0) / maxQuantity) * 100, 100)}%` }}
                  />
                </div>
              ))
            )}
          </div>

          <div className="bids-list">
            {bids.length === 0 ? (
              <div className="order-row" style={{ color: '#666', justifyContent: 'center' }}>
                매수 호가 수신 대기 중…
              </div>
            ) : (
              bids.map((bid) => (
                <div
                  key={`bid-${bid.level}`}
                  className="order-row bid"
                  onClick={() => handleSelectPrice(bid.price)}
                >
                  <span className="col-price">{bid.price?.toLocaleString() ?? '-'}</span>
                  <span className="col-amount">{bid.quantity?.toLocaleString() ?? '-'}</span>
                  <div
                    className="bg-bar"
                    style={{ width: `${Math.min(((bid.quantity ?? 0) / maxQuantity) * 100, 100)}%` }}
                  />
                </div>
              ))
            )}
          </div>
        </div>

        {/* 주문 폼 — 백엔드 Order API 미구현 상태라 placeholder */}
        <div className="order-form">
          <div className="order-type-switch">
            <button
              className={`type-btn buy ${orderType === 'BUY' ? 'active' : ''}`}
              onClick={() => setOrderType('BUY')}
            >
              매수
            </button>
            <button
              className={`type-btn sell ${orderType === 'SELL' ? 'active' : ''}`}
              onClick={() => setOrderType('SELL')}
            >
              매도
            </button>
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
    </div>
  );
}
