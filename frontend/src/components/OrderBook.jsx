import { useState, useEffect, useMemo } from 'react';
import { placeOrder } from '../api/order';
// 미체결/주문가능/정정취소 API는 백엔드 미구현 또는 워킹트리 미반영 상태
// import { getBuyingPower, getPendingOrders, updateOrder } from '../api/order';
import './OrderBook.css';

export default function OrderBook({ stockCode }) {
  const [orderType, setOrderType] = useState('BUY'); // 'BUY' | 'SELL'

  const [price, setPrice] = useState(0);
  const [quantity, setQuantity] = useState(1);
  const [submittingOrder, setSubmittingOrder] = useState(false);

  // 실시간 호가 (OrderbookResponse): asks/bids는 OrderbookLevel[] = { level, price, quantity }
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

  // 수동 매수/매도 주문 (POST /api/v1/orders)
  // 현재 화면이 단가 입력형이라 LIMIT 고정. 추후 시장가 옵션 추가 시 orderMethod 토글 필요.
  const handleOrder = async () => {
    if (submittingOrder) return;
    if (!stockCode) {
      alert('종목이 선택되지 않았습니다.');
      return;
    }
    if (!Number.isFinite(Number(price)) || Number(price) <= 0) {
      alert('단가를 입력해 주세요.');
      return;
    }
    if (!Number.isFinite(Number(quantity)) || Number(quantity) < 1) {
      alert('수량은 1 이상이어야 합니다.');
      return;
    }

    setSubmittingOrder(true);
    try {
      const result = await placeOrder({
        stockCode,
        side: orderType,
        orderMethod: 'LIMIT',
        quantity: Number(quantity),
        price: Number(price),
      });
      alert(`주문이 접수되었습니다. (주문번호: ${result.orderId}, 상태: ${result.status})`);
    } catch (error) {
      console.error('주문 실패:', error);
      alert(error.message || '주문에 실패했습니다.');
    } finally {
      setSubmittingOrder(false);
    }
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

          <button
            className={`submit-order-btn ${orderType.toLowerCase()}`}
            onClick={handleOrder}
            disabled={submittingOrder}
          >
            {submittingOrder ? '접수 중…' : (orderType === 'BUY' ? '매수 주문' : '매도 주문')}
          </button>
        </div>
      </div>
    </div>
  );
}
