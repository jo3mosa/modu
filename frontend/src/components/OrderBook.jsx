import { useState, useEffect, useMemo, useRef, useCallback } from 'react';
import { placeOrder, getPendingOrders, updateOrder, getBuyingPower } from '../api/order';
import './OrderBook.css';

export default function OrderBook({ stockCode }) {
  const [activeTab, setActiveTab] = useState('ORDER'); // 'ORDER' | 'PENDING'
  const [orderType, setOrderType] = useState('BUY'); // 'BUY' | 'SELL'
  const [orderMethod, setOrderMethod] = useState('LIMIT'); // 'LIMIT' | 'MARKET'

  const [price, setPrice] = useState(0);
  const [quantity, setQuantity] = useState(1);
  const [submittingOrder, setSubmittingOrder] = useState(false);
  // 주문당 고유 키 생성 → 동일 키로 중복 호출 시 백엔드에서 멱등성 보호
  const idempotencyKeyRef = useRef(crypto.randomUUID());

  // 실시간 호가 (OrderbookResponse): asks/bids는 OrderbookLevel[] = { level, price, quantity }
  const [asks, setAsks] = useState([]);
  const [bids, setBids] = useState([]);

  // 미체결 주문 목록 (PendingOrdersResponse.pendingOrders[])
  const [pendingOrders, setPendingOrders] = useState([]);
  const [loadingPending, setLoadingPending] = useState(false);
  // 주문별 취소 진행 상태 { [orderId]: true }
  const [canceling, setCanceling] = useState({});

  // 매수가능 정보 (연결 실패 시 → null 시 영역 미표시)
  // { maxBuyAmount, maxBuyQuantity, maxSellQuantity, availableCash }
  const [buyingPower, setBuyingPower] = useState(null);
  const buyingPowerTimerRef = useRef(null);

  // 호가 WebSocket 연결: /ws/stocks/{code}/orderbook
  useEffect(() => {
    if (!stockCode) return;

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const ws = new WebSocket(`${protocol}//${window.location.host}/ws/stocks/${stockCode}/orderbook`);

    ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
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

  // 미체결 주문 조회 (GET /api/v1/orders/pending)
  const fetchPending = useCallback(async () => {
    setLoadingPending(true);
    try {
      const list = await getPendingOrders();
      const filtered = stockCode
        ? list.filter((o) => o.stockCode === stockCode)
        : list;
      setPendingOrders(filtered);
    } catch (error) {
      if (error.status !== 404) {
        console.warn('미체결 주문 조회 실패:', error);
      }
      setPendingOrders([]);
    } finally {
      setLoadingPending(false);
    }
  }, [stockCode]);

  // 미체결 탭 진입 또는 종목 변경 시 조회
  useEffect(() => {
    if (activeTab === 'PENDING') fetchPending();
  }, [activeTab, fetchPending]);

  // 매수가능 정보 조회: side/stockCode 변경 시 1초 debounce로 호출.
  // (price 변경마다 호출하면 KIS 초당 거래건수 한도(EGW00201)에 걸려서 의존성에서 제외)
  // 연결 실패 시 404 → null 유지 → 화면 영역 미표시.
  useEffect(() => {
    if (!stockCode) {
      setBuyingPower(null);
      return;
    }
    if (buyingPowerTimerRef.current) clearTimeout(buyingPowerTimerRef.current);

    buyingPowerTimerRef.current = setTimeout(async () => {
      try {
        const data = await getBuyingPower({
          stockCode,
          side: orderType,
        });
        setBuyingPower(data ?? null);
      } catch (error) {
        if (error.status !== 404) {
          console.warn('매수가능 조회 실패:', error);
        }
        setBuyingPower(null);
      }
    }, 1000);

    return () => {
      if (buyingPowerTimerRef.current) clearTimeout(buyingPowerTimerRef.current);
    };
  }, [stockCode, orderType]);

  const maxQuantity = useMemo(() => {
    const allQuantities = [...asks, ...bids].map((lv) => lv?.quantity ?? 0);
    return Math.max(1, ...allQuantities);
  }, [asks, bids]);

  const handleSelectPrice = (lvPrice) => {
    if (typeof lvPrice === 'number') setPrice(lvPrice);
  };

  // 수동 매수/매도 주문 (POST /api/v1/orders)
  // orderMethod: LIMIT(지정가) / MARKET(시장가).
  // - LIMIT: 사용자 입력 price 사용
  // - MARKET: price 0 전송 (백엔드가 ORD_DVSN=01로 처리)
  const handleOrder = async () => {
    if (submittingOrder) return;
    if (!stockCode) {
      alert('종목이 선택되지 않았습니다.');
      return;
    }
    if (orderMethod === 'LIMIT' && (!Number.isFinite(Number(price)) || Number(price) <= 0)) {
      alert('단가를 입력해 주세요.');
      return;
    }
    if (!Number.isFinite(Number(quantity)) || Number(quantity) < 1) {
      alert('수량은 1 이상이어야 합니다.');
      return;
    }

    setSubmittingOrder(true);
    try {
      const result = await placeOrder(
        {
          stockCode,
          side: orderType,
          orderMethod,
          quantity: Number(quantity),
          price: orderMethod === 'MARKET' ? 0 : Number(price),
        },
        idempotencyKeyRef.current
      );
      idempotencyKeyRef.current = crypto.randomUUID();
      alert(`주문이 접수되었습니다. (주문번호: ${result.orderId}, 상태: ${result.status})`);
      // 주문 접수 직후 미체결 목록 갱신
      fetchPending();
    } catch (error) {
      console.error('주문 실패:', error);
      alert(error.message || '주문에 실패했습니다.');
    } finally {
      setSubmittingOrder(false);
    }
  };

  // 미체결 주문 취소 (PATCH /api/v1/orders/{orderId}, action=CANCEL)
  // DB에 orderId가 있는 주문만 취소 가능 (KIS 외부 주문은 orderId가 null)
  const handleCancelOrder = async (order) => {
    if (!order?.orderId) {
      alert('시스템에 기록된 주문만 취소할 수 있습니다.');
      return;
    }
    if (canceling[order.orderId]) return;
    if (!window.confirm(`주문(${order.orderId})을 취소하시겠습니까?`)) return;

    setCanceling((prev) => ({ ...prev, [order.orderId]: true }));
    try {
      await updateOrder(order.orderId, { action: 'CANCEL' });
      alert('주문이 취소되었습니다.');
      fetchPending();
    } catch (error) {
      console.error('주문 취소 실패:', error);
      alert(error.message || '주문 취소에 실패했습니다.');
    } finally {
      setCanceling((prev) => {
        const next = { ...prev };
        delete next[order.orderId];
        return next;
      });
    }
  };

  return (
    <div className="orderbook-wrapper">
      {/* 탭 헤더 */}
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
          {pendingOrders.length > 0 && ` (${pendingOrders.length})`}
        </button>
      </div>

      {/* 호가·주문 탭 */}
      {activeTab === 'ORDER' && (
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

          {/* 주문 폼 */}
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

            {/* 주문 방식 토글: 지정가 / 시장가 */}
            <div className="order-method-switch" style={{ display: 'flex', gap: '0.4rem', marginTop: '0.5rem' }}>
              <button
                className={`tool-btn ${orderMethod === 'LIMIT' ? 'active' : ''}`}
                style={{ flex: 1, padding: '0.4rem 0.5rem' }}
                onClick={() => setOrderMethod('LIMIT')}
              >
                지정가
              </button>
              <button
                className={`tool-btn ${orderMethod === 'MARKET' ? 'active' : ''}`}
                style={{ flex: 1, padding: '0.4rem 0.5rem' }}
                onClick={() => setOrderMethod('MARKET')}
              >
                시장가
              </button>
            </div>

            {/* 매수가능 정보 — 백엔드 미연결 시 표시 안 함 */}
            {buyingPower && (
              <div className="buying-power-info" style={{ fontSize: '0.85em', color: '#aaa', margin: '0.5rem 0' }}>
                {orderType === 'BUY' ? (
                  <>
                    <div>주문 가능 금액: <strong style={{ color: '#fff' }}>{buyingPower.maxBuyAmount?.toLocaleString() ?? '-'}원</strong></div>
                    <div>최대 매수 가능 수량: {buyingPower.maxBuyQuantity?.toLocaleString() ?? '-'}주</div>
                    <div>예수금: {buyingPower.availableCash?.toLocaleString() ?? '-'}원</div>
                  </>
                ) : (
                  <div>매도 가능 수량: <strong style={{ color: '#fff' }}>{buyingPower.maxSellQuantity?.toLocaleString() ?? '-'}주</strong></div>
                )}
              </div>
            )}

            {/* 매도인데 보유 수량 0이면 안내 + 버튼 비활성화 */}
            {orderType === 'SELL' && buyingPower && (buyingPower.maxSellQuantity ?? 0) === 0 && (
              <div
                style={{
                  fontSize: '0.85em',
                  color: '#ef4444',
                  background: 'rgba(239,68,68,0.1)',
                  padding: '0.5rem 0.75rem',
                  borderRadius: '6px',
                  margin: '0.5rem 0',
                }}
              >
                보유 종목이 없습니다. 매도할 수 없어요.
              </div>
            )}

            <div className="input-group">
              <label>단가 (원)</label>
              <input
                type="number"
                value={orderMethod === 'MARKET' ? '' : price}
                onChange={(e) => setPrice(Number(e.target.value))}
                disabled={orderMethod === 'MARKET'}
                placeholder={orderMethod === 'MARKET' ? '시장가 (체결 시점 가격)' : ''}
              />
            </div>
            <div className="input-group">
              <label>수량 (주)</label>
              <input type="number" value={quantity} onChange={(e) => setQuantity(Number(e.target.value))} />
            </div>

            <div className="order-total">
              <span>총 주문 금액</span>
              <strong>
                {orderMethod === 'MARKET'
                  ? '시장가'
                  : `${(price * quantity).toLocaleString()}원`}
              </strong>
            </div>

            <button
              className={`submit-order-btn ${orderType.toLowerCase()}`}
              onClick={handleOrder}
              disabled={
                submittingOrder ||
                (orderType === 'SELL' && buyingPower && (buyingPower.maxSellQuantity ?? 0) === 0)
              }
            >
              {submittingOrder ? '접수 중…' : (orderType === 'BUY' ? '매수 주문' : '매도 주문')}
            </button>
          </div>
        </div>
      )}

      {/* 미체결 내역 탭 */}
      {activeTab === 'PENDING' && (
        <div className="pending-content">
          <div className="pending-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '0.5rem 0' }}>
            <span style={{ color: '#aaa', fontSize: '0.9em' }}>
              {stockCode ? `현재 종목 ${stockCode} 기준` : '전체 종목'}
            </span>
            <button
              className="tool-btn"
              onClick={fetchPending}
              disabled={loadingPending}
              style={{ fontSize: '0.85em' }}
            >
              {loadingPending ? '새로고침 중 …' : '새로고침'}
            </button>
          </div>

          {loadingPending && pendingOrders.length === 0 ? (
            <div className="empty-state">미체결 주문을 불러오는 중입니다 …</div>
          ) : pendingOrders.length === 0 ? (
            <div className="empty-state">미체결 주문이 없습니다.</div>
          ) : (
            <div className="pending-list">
              {pendingOrders.map((order, idx) => {
                const key = order.orderId ?? `${order.stockCode}-${idx}`;
                const sideLabel = order.side === 'BUY' ? '매수' : '매도';
                return (
                  <div key={key} className="pending-item">
                    <div className="pending-info">
                      <span className={`pending-type ${(order.side ?? '').toLowerCase()}`}>
                        {sideLabel}
                      </span>
                      <div className="pending-details">
                        <strong>{order.stockName ?? order.stockCode}</strong>
                        <span>
                          {order.price?.toLocaleString() ?? '-'}원 · {order.quantity?.toLocaleString() ?? '-'}주
                        </span>
                        <span style={{ color: '#888', fontSize: '0.85em' }}>
                          체결 {order.filledQuantity ?? 0} / 미체결 {order.remainQuantity ?? 0}
                        </span>
                        {order.createdAt && (
                          <span className="pending-time" style={{ fontSize: '0.85em', color: '#888' }}>
                            {order.createdAt}
                          </span>
                        )}
                      </div>
                    </div>
                    <div className="pending-actions">
                      <button
                        className="cancel-btn"
                        onClick={() => handleCancelOrder(order)}
                        disabled={!order.orderId || !!canceling[order.orderId]}
                        title={!order.orderId ? '시스템에 기록된 주문만 취소 가능' : ''}
                      >
                        {canceling[order.orderId] ? '취소 중 …' : '취소'}
                      </button>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      )}
    </div>
  );
}