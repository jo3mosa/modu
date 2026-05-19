import { useState, useEffect, useMemo, useRef, useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';
import { toast } from 'sonner';
import { placeOrder, getPendingOrders, updateOrder, getBuyingPower } from '../api/order';
import { getStockDetail } from '../api/market';
import { buildStockWsUrl } from '../api/wsUrl';
import { useOrderSSE } from '../hooks/useOrderSSE';
import { useNotifications } from '../hooks/useNotifications';
import ConfirmDialog from './ConfirmDialog';
import './OrderBook.css';
import { getPortfolio } from '../api/account';

export default function OrderBook({ stockCode }) {
  const [activeTab, setActiveTab] = useState('ORDER'); // 'ORDER' | 'PENDING' | 'HOLDING'
  const [, setSearchParams] = useSearchParams();
  const [holdings, setHoldings] = useState([]);
  const [loadingHoldings, setLoadingHoldings] = useState(false);
  const [orderType, setOrderType] = useState('BUY'); // 'BUY' | 'SELL'
  const [orderMethod, setOrderMethod] = useState('LIMIT'); // 'LIMIT' | 'MARKET'

  const [price, setPrice] = useState(0);

  // 종목 변경 시 상세 데이터 로드하여 단가 초기값으로 세팅
  useEffect(() => {
    if (!stockCode) return;
    let cancelled = false;
    async function initPrice() {
      try {
        const detail = await getStockDetail(stockCode);
        if (!cancelled && detail?.currentPrice) {
          setPrice(detail.currentPrice);
        }
      } catch (error) {
        console.warn('초기 단가 조회 실패:', error);
      }
    }
    initPrice();
    return () => {
      cancelled = true;
    };
  }, [stockCode]);

  /**
   * 한국 주식시장(KRX)의 가격대별 호가 단위(Tick Size) 반환
   */
  const getKrxTickSize = (priceVal) => {
    const p = Number(priceVal);
    if (isNaN(p) || p <= 0) return 1;
    if (p < 2000) return 1;
    if (p < 5000) return 5;
    if (p < 20000) return 10;
    if (p < 50000) return 50;
    if (p < 200000) return 100;
    if (p < 500000) return 500;
    return 1000;
  };

  const handleIncreasePrice = () => {
    setPrice((prev) => {
      const step = getKrxTickSize(prev);
      return prev + step;
    });
  };

  const handleDecreasePrice = () => {
    setPrice((prev) => {
      const step = getKrxTickSize(prev);
      return Math.max(0, prev - step);
    });
  };
  const [quantity, setQuantity] = useState(1);

  const handleIncreaseQuantity = () => {
    setQuantity((prev) => (Number.isFinite(prev) ? prev : 0) + 1);
  };

  const handleDecreaseQuantity = () => {
    setQuantity((prev) => Math.max(0, (Number.isFinite(prev) ? prev : 0) - 1));
  };
  const [submittingOrder, setSubmittingOrder] = useState(false);
  const [pendingSubmitOrderId, setPendingSubmitOrderId] = useState(null);
  const submitTimeoutRef = useRef(null);

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
  // 정정 중인 주문: { orderId, newPrice, newQuantity } | null
  const [modifyingOrder, setModifyingOrder] = useState(null);
  // 주문별 정정 API 호출 중 여부 { [orderId]: true }
  const [modifying, setModifying] = useState({});

  // 매수가능 정보 (연결 실패 시 → null 시 영역 미표시)
  // { maxBuyAmount, maxBuyQuantity, maxSellQuantity, availableCash }
  const [buyingPower, setBuyingPower] = useState(null);
  const buyingPowerTimerRef = useRef(null);

  // 호가 WebSocket 연결: /ws/stocks/{code}/orderbook
  useEffect(() => {
    if (!stockCode) return;

    const ws = new WebSocket(buildStockWsUrl(stockCode, 'orderbook'));

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
      setPendingOrders(list);
    } catch (error) {
      if (error.status !== 404) {
        console.warn('미체결 주문 조회 실패:', error);
      }
      setPendingOrders([]);
    } finally {
      setLoadingPending(false);
    }
  }, []);

  // 보유현황 조회 (GET /api/v1/accounts/me/holdings)
  const fetchHoldings = useCallback(async () => {
    setLoadingHoldings(true);
    try {
      const data = await getPortfolio();
      setHoldings(data?.holdings ?? []);
    } catch (error) {
      console.warn('보유현황 조회 실패:', error);
      setHoldings([]);
    } finally {
      setLoadingHoldings(false);
    }
  }, []);

  // 미체결 탭 진입 또는 종목 변경 시 조회
  useEffect(() => {
    if (activeTab === 'PENDING') fetchPending();
  }, [activeTab, fetchPending]);

  // 보유현황 탭 진입 시 조회
  useEffect(() => {
    if (activeTab === 'HOLDING') fetchHoldings();
  }, [activeTab, fetchHoldings]);

  // 전역 SSE 구독 — Provider 가 단일 EventSource 를 유지하고 최신 이벤트만 흘려준다.
  // (useOrderSSE 는 인자를 받지 않는 Context 훅이라 콜백 등록 방식이 아닌 latestEvent watch 패턴 사용)
  const { latestEvent } = useOrderSSE();

  // 매수가능 정보 조회 — 즉시 fetch. 주문 접수/체결/취소/정정 후 즉시 호출 가능.
  // 연결 실패 시 404 → null 유지 → 화면 영역 미표시.
  // (SSE useEffect 의존성에 들어가므로 그보다 먼저 선언되어야 TDZ 회피)
  const fetchBuyingPower = useCallback(async () => {
    if (!stockCode) {
      setBuyingPower(null);
      return;
    }
    try {
      const data = await getBuyingPower({ stockCode, side: orderType });
      setBuyingPower(data ?? null);
    } catch (error) {
      if (error.status !== 404) {
        console.warn('매수가능 조회 실패:', error);
      }
      setBuyingPower(null);
    }
  }, [stockCode, orderType]);

  const { addNotification } = useNotifications();

  // SSE 이벤트 → 주문 상태 동기화
  // ORDER_SUBMITTED        : 비동기 주문의 KIS 접수 완료. 대기 중 5초 타임아웃 해제 후 미체결 갱신.
  // ORDER_FAILED           : KIS 접수 거절. message에 Kill Switch 키워드 있으면 알림 목록에도 추가.
  // ORDER_EXECUTED         : 체결 완료. 미체결 탭이면 해당 주문이 빠진 목록으로 즉시 갱신.
  // ORDER_RESERVED         : KIS 예약주문 접수. 다음 거래일 정규장 시작 시 자동 실행.
  // ORDER_RESERVED_PENDING : 예약 가능 시간 대기 중 (E gap 10분 또는 공휴일 정규장 시간대).
  useEffect(() => {
    if (!latestEvent) return;

    if (latestEvent.type === 'ORDER_SUBMITTED') {
      setPendingSubmitOrderId((prevId) => {
        if (prevId === String(latestEvent.orderId)) {
          if (submitTimeoutRef.current) clearTimeout(submitTimeoutRef.current);
          setSubmittingOrder(false);
          toast.success('주문이 접수되었습니다');
          fetchPending();
          return null;
        }
        return prevId;
      });
      // 매수 주문이 KIS에 접수되면 가용 현금이 잠기므로 매수가능 즉시 갱신
      fetchBuyingPower();
    } else if (latestEvent.type === 'ORDER_FAILED') {
      // 백엔드가 KIS 원본 메시지를 SYS_002("외부 API 호출 중 오류")로 뭉뚱그리는 경우가 있어
      // 사용자 입장 의미 없는 메시지를 보게 됨 — 키워드 기반으로 친화적 안내 치환.
      // (근본 해결은 백엔드에서 KIS msg/msgCd 보존하도록 수정)
      const rawMsg = String(latestEvent.message ?? '');
      const friendlyMsg = /외부\s*API|SYS_002|EXTERNAL_API/i.test(rawMsg)
        ? '주문이 거절되었습니다. 가격이 상/하한가 범위 안인지, 잔고와 보유 수량이 충분한지 확인해주세요.'
        : (rawMsg || '잔고 부족 등의 사유로 거절되었습니다.');
      setPendingSubmitOrderId((prevId) => {
        if (prevId === String(latestEvent.orderId)) {
          if (submitTimeoutRef.current) clearTimeout(submitTimeoutRef.current);
          setSubmittingOrder(false);
          toast.error('주문 실패', { description: friendlyMsg });
          return null;
        }
        return prevId;
      });
      // 접수 실패 시 잠겼던 자금 해제 — 정확한 잔고 반영 위해 갱신
      fetchBuyingPower();
      // Kill Switch 키워드 감지 — 백엔드가 별도 푸시를 안 주니 메시지에서 추론
      const msg = String(latestEvent.message ?? '');
      if (/kill[\s_]*switch|강제\s*중단/i.test(msg)) {
        addNotification({
          type: 'KILL_SWITCH',
          message: '자동매매 강제 중단됨',
          description: msg || '안전 한도 초과 — Kill Switch가 발동되었습니다.',
        });
      }
    } else if (latestEvent.type === 'ORDER_EXECUTED') {
      if (latestEvent.stockCode === stockCode && activeTab === 'PENDING') {
        fetchPending();
      }
      // 체결로 자산/잔고 변동 — 매수가능 즉시 갱신
      fetchBuyingPower();
    } else if (latestEvent.type === 'ORDER_RESERVED') {
      // 예약주문 접수 — 다음 거래일 정규장 시작 시 자동 실행
      setPendingSubmitOrderId((prevId) => {
        if (prevId === String(latestEvent.orderId)) {
          if (submitTimeoutRef.current) clearTimeout(submitTimeoutRef.current);
          setSubmittingOrder(false);
          return null;
        }
        return prevId;
      });
      toast.success('예약주문이 접수되었습니다', {
        description: '다음 거래일 정규장 시작 시 자동 실행됩니다.',
      });
      fetchPending();
    } else if (latestEvent.type === 'ORDER_RESERVED_PENDING') {
      // 예약 가능 시간 도래 전 발행 대기
      toast.info('예약주문 발행 대기 중', {
        description: '예약 가능 시간 도래 시 자동으로 발행됩니다.',
      });
    }
  }, [latestEvent, fetchPending, fetchBuyingPower, stockCode, activeTab, addNotification]);

  // side/stockCode 변경 시 1초 debounce로 호출.
  // (price 변경마다 호출하면 KIS 초당 거래건수 한도(EGW00201)에 걸려서 의존성에서 제외)
  useEffect(() => {
    if (!stockCode) {
      setBuyingPower(null);
      return;
    }
    if (buyingPowerTimerRef.current) clearTimeout(buyingPowerTimerRef.current);
    buyingPowerTimerRef.current = setTimeout(fetchBuyingPower, 1000);

    return () => {
      if (buyingPowerTimerRef.current) clearTimeout(buyingPowerTimerRef.current);
    };
  }, [fetchBuyingPower]);

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
      toast.error('종목이 선택되지 않았습니다.');
      return;
    }
    if (orderMethod === 'LIMIT' && (!Number.isFinite(Number(price)) || Number(price) <= 0)) {
      toast.error('단가를 입력해 주세요.');
      return;
    }
    if (!Number.isFinite(Number(quantity)) || Number(quantity) < 1) {
      toast.error('수량은 1 이상이어야 합니다.');
      return;
    }

    // 사전 차단: 백엔드/KIS 거절 전 클라이언트에서 즉시 막아 사용자 피드백 명확화
    // (buyingPower는 1초 debounce로 갱신되므로 직전에 side/stockCode 빠르게 전환한 경우 stale 가능 — 그땐 백엔드 검증으로 fallback)
    if (buyingPower) {
      const qty = Number(quantity);
      if (orderType === 'BUY') {
        if (orderMethod === 'LIMIT') {
          const px = Number(price);
          if (px > 0 && buyingPower.maxBuyAmount != null) {
            const total = px * qty;
            if (total > buyingPower.maxBuyAmount) {
              toast.error('주문 가능 금액을 초과합니다', {
                description: `주문 금액: ${total.toLocaleString()}원 / 가능 금액: ${buyingPower.maxBuyAmount.toLocaleString()}원`,
              });
              return;
            }
          }
        } else {
          // MARKET: 단가 미정이라 수량으로만 검증
          if (buyingPower.maxBuyQuantity != null && qty > buyingPower.maxBuyQuantity) {
            toast.error('주문 가능 수량을 초과합니다', {
              description: `주문 수량: ${qty.toLocaleString()}주 / 가능 수량: ${buyingPower.maxBuyQuantity.toLocaleString()}주`,
            });
            return;
          }
        }
      } else if (orderType === 'SELL') {
        if (buyingPower.maxSellQuantity != null && qty > buyingPower.maxSellQuantity) {
          toast.error('보유 수량을 초과합니다', {
            description: `주문 수량: ${qty.toLocaleString()}주 / 보유 수량: ${buyingPower.maxSellQuantity.toLocaleString()}주`,
          });
          return;
        }
      }
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

      // 하위 호환성 분기 로직
      // 백엔드 응답에 kisOrderNo가 있거나 status가 PENDING이 아니면 기존(동기) 모드
      if (result.kisOrderNo || result.status !== 'PENDING') {
        toast.success('주문이 접수되었습니다');
        fetchPending();
        fetchBuyingPower();
        setSubmittingOrder(false); // 즉시 해제
      } else {
        // 비동기 모드: 응답이 PENDING이면 SSE 수신 대기
        setPendingSubmitOrderId(String(result.orderId));

        if (submitTimeoutRef.current) clearTimeout(submitTimeoutRef.current);
        submitTimeoutRef.current = setTimeout(() => {
          setPendingSubmitOrderId((prevId) => {
            if (prevId === String(result.orderId)) {
              setSubmittingOrder(false);
              toast.warning('주문 결과 확인 지연', {
                description: '미체결 내역을 확인해주세요.',
              });
              fetchPending(); // 늦게라도 처리됐을 수 있으니 1회 갱신
              return null;
            }
            return prevId;
          });
        }, 5000);
      }
    } catch (error) {
      console.error('주문 실패:', error);
      toast.error(error.message || '주문에 실패했습니다.');
      setSubmittingOrder(false); // 에러 시 즉시 해제
    }
  };

  // 주문 취소 확인 다이얼로그 상태 — null이면 닫힘. 객체면 해당 주문 취소 confirm 표시
  const [cancelConfirm, setCancelConfirm] = useState(null);

  // 미체결 주문 취소 (PATCH /api/v1/orders/{orderId}, action=CANCEL)
  // DB에 orderId가 있는 주문만 취소 가능 (KIS 외부 주문은 orderId가 null)
  const handleCancelOrder = (order) => {
    if (!order?.orderId) {
      toast.error('시스템에 기록된 주문만 취소할 수 있습니다.');
      return;
    }
    if (canceling[order.orderId]) return;
    // window.confirm → 커스텀 ConfirmDialog로 교체
    setCancelConfirm(order);
  };

  const executeCancelOrder = async (order) => {
    setCanceling((prev) => ({ ...prev, [order.orderId]: true }));
    try {
      await updateOrder(order.orderId, { action: 'CANCEL' });
      toast.success('주문이 취소되었습니다.');
      fetchPending();
      fetchBuyingPower();
    } catch (error) {
      console.error('주문 취소 실패:', error);
      toast.error(error.message || '주문 취소에 실패했습니다.');
    } finally {
      setCanceling((prev) => {
        const next = { ...prev };
        delete next[order.orderId];
        return next;
      });
    }
  };

  // 정정 폼 열기: 현재 주문의 price/remainQuantity를 초기값으로 세팅
  const handleOpenModify = (order) => {
    setModifyingOrder({
      orderId: order.orderId,
      newPrice: order.price ?? 0,
      newQuantity: order.remainQuantity ?? order.quantity ?? 1,
    });
  };

  const handleCloseModify = () => setModifyingOrder(null);

  // 정정 저장 (PATCH /api/v1/orders/{orderId}, action=MODIFY)
  // 백엔드: newPrice or newQuantity 중 하나 이상 필수, 각 @Min(1)
  const handleSaveModify = async () => {
    if (!modifyingOrder) return;
    const { orderId, newPrice, newQuantity } = modifyingOrder;
    if (modifying[orderId]) return;

    const parsedPrice = Number(newPrice);
    const parsedQty = Number(newQuantity);
    if (parsedPrice < 1 || parsedQty < 1) {
      toast.error('단가와 수량은 1 이상이어야 합니다.');
      return;
    }

    setModifying((prev) => ({ ...prev, [orderId]: true }));
    try {
      await updateOrder(orderId, {
        action: 'MODIFY',
        newPrice: parsedPrice,
        newQuantity: parsedQty,
      });
      toast.success('주문이 정정되었습니다.');
      setModifyingOrder(null);
      fetchPending();
      fetchBuyingPower();
    } catch (error) {
      console.error('주문 정정 실패:', error);
      toast.error(error.message || '주문 정정에 실패했습니다.');
    } finally {
      setModifying((prev) => {
        const next = { ...prev };
        delete next[orderId];
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
          className={`tab-btn ${activeTab === 'HOLDING' ? 'active' : ''}`}
          onClick={() => setActiveTab('HOLDING')}
        >
          보유현황
        </button>
        <button
          className={`tab-btn ${activeTab === 'PENDING' ? 'active' : ''}`}
          onClick={() => setActiveTab('PENDING')}
        >
          미체결 내역
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
                  매도 호가 수신 대기 중 …
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
                  매수 호가 수신 대기 중 …
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
              <div className="buying-power-info">
                {orderType === 'BUY' ? (
                  <>
                    <div className="buying-power-row">
                      <span>주문 가능 금액</span>
                      <strong>{buyingPower.maxBuyAmount?.toLocaleString() ?? '-'}원</strong>
                    </div>
                    <div className="buying-power-row">
                      <span>최대 매수 수량</span>
                      <strong>{buyingPower.maxBuyQuantity?.toLocaleString() ?? '-'}주</strong>
                    </div>
                    <div className="buying-power-row">
                      <span>예수금</span>
                      <strong>{buyingPower.availableCash?.toLocaleString() ?? '-'}원</strong>
                    </div>
                  </>
                ) : (
                  <div className="buying-power-row">
                    <span>매도 가능 수량</span>
                    <strong>{buyingPower.maxSellQuantity?.toLocaleString() ?? '-'}주</strong>
                  </div>
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
                보유 종목이 없습니다.
              </div>
            )}

            <div className="input-group price-input-group">
              <label>단가 (원)</label>
              <div className="price-control-container">
                <input
                  type="number"
                  className="price-field"
                  value={orderMethod === 'MARKET' ? '' : price}
                  onChange={(e) => setPrice(Number(e.target.value))}
                  disabled={orderMethod === 'MARKET'}
                  placeholder={orderMethod === 'MARKET' ? '시장가' : ''}
                />
                <div className="price-btn-group">
                  <button
                    type="button"
                    className="price-step-btn minus"
                    onClick={handleDecreasePrice}
                    disabled={orderMethod === 'MARKET'}
                    aria-label="가격 낮추기"
                  >
                    -
                  </button>
                  <button
                    type="button"
                    className="price-step-btn plus"
                    onClick={handleIncreasePrice}
                    disabled={orderMethod === 'MARKET'}
                    aria-label="가격 높이기"
                  >
                    +
                  </button>
                </div>
              </div>
            </div>
            <div className="input-group">
              <label>수량 (주)</label>
              <div className="price-control-container">
                <input
                  type="number"
                  className="price-field"
                  value={quantity}
                  onChange={(e) => setQuantity(Number(e.target.value))}
                />
                <div className="price-btn-group">
                  <button
                    type="button"
                    className="price-step-btn minus"
                    onClick={handleDecreaseQuantity}
                    aria-label="수량 감소"
                  >
                    -
                  </button>
                  <button
                    type="button"
                    className="price-step-btn plus"
                    onClick={handleIncreaseQuantity}
                    aria-label="수량 증가"
                  >
                    +
                  </button>
                </div>
              </div>
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

      {/* 보유현황 탭 */}
      {activeTab === 'HOLDING' && (
        <div className="holding-content">
          <div className="holding-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '0.5rem 0' }}>
            <span style={{ color: '#aaa', fontSize: '0.9em' }}>실시간 보유 자산</span>
            <button
              className="tool-btn"
              onClick={fetchHoldings}
              disabled={loadingHoldings}
              style={{ fontSize: '0.85em' }}
            >
              {loadingHoldings ? '새로고침 중 …' : '새로고침'}
            </button>
          </div>

          {loadingHoldings && holdings.length === 0 ? (
            <div className="empty-state">보유 자산을 불러오는 중입니다 …</div>
          ) : holdings.length === 0 ? (
            <div className="empty-state">보유 중인 주식이 없습니다.</div>
          ) : (
            <div className="holdings-inner-container">
              {/* 1. 현재 선택 종목 보유 요약 카드 */}
              {(() => {
                const currentHolding = holdings.find(h => h.stockCode === stockCode);
                if (!currentHolding) return null;
                const isProfit = (currentHolding.pnl ?? 0) >= 0;
                const pnlSign = isProfit ? '+' : '';
                const pnlColorClass = isProfit ? 'up' : 'down';

                return (
                  <div className="current-holding-card">
                    <div className="card-top">
                      <div className="stock-meta">
                        <span className="card-stock-name">{currentHolding.stockName}</span>
                        <span className="card-stock-code">{currentHolding.stockCode}</span>
                      </div>
                      <span className={`card-pnl-pct ${pnlColorClass}`}>
                        {pnlSign}{currentHolding.pnlPct?.toFixed(2) ?? '0.00'}%
                      </span>
                    </div>

                    <div className="card-body-grid">
                      <div className="card-field">
                        <span className="field-lbl">보유 수량</span>
                        <strong className="field-val">{currentHolding.quantity?.toLocaleString() ?? '0'}주</strong>
                      </div>
                      <div className="card-field">
                        <span className="field-lbl">평균 매수가</span>
                        <strong className="field-val">{currentHolding.avgBuyPrice?.toLocaleString() ?? '0'}원</strong>
                      </div>
                      <div className="card-field">
                        <span className="field-lbl">현재 평가금액</span>
                        <strong className="field-val">
                          {((currentHolding.currentPrice ?? 0) * (currentHolding.quantity ?? 0)).toLocaleString()}원
                        </strong>
                      </div>
                      <div className="card-field">
                        <span className="field-lbl">평가 손익</span>
                        <strong className={`field-val ${pnlColorClass}`}>
                          {pnlSign}{currentHolding.pnl?.toLocaleString() ?? '0'}원
                        </strong>
                      </div>
                    </div>
                  </div>
                );
              })()}

              {/* 2. 전체 보유 종목 미니 리스트 */}
              <div className="other-holdings-title">전체 보유 종목</div>
              <div className="holdings-list">
                {holdings.map((h) => {
                  const isCurrent = h.stockCode === stockCode;
                  const isProfit = (h.pnl ?? 0) >= 0;
                  const pnlSign = isProfit ? '+' : '';
                  const pnlColorClass = isProfit ? 'up' : 'down';

                  return (
                    <div
                      key={h.stockCode}
                      className={`holding-item-row ${isCurrent ? 'selected' : ''}`}
                      onClick={() => setSearchParams({ stock: h.stockCode })}
                      title="클릭 시 이 종목으로 차트 및 호가창이 전환됩니다"
                    >
                      <div className="item-left">
                        <span className="item-name">{h.stockName}</span>
                        <span className="item-qty">{h.quantity?.toLocaleString() ?? 0}주</span>
                      </div>
                      <div className="item-right">
                        <span className="item-price">{h.currentPrice?.toLocaleString() ?? 0}원</span>
                        <span className={`item-pnl-pct ${pnlColorClass}`}>
                          {pnlSign}{h.pnlPct?.toFixed(2) ?? '0.00'}%
                        </span>
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          )}
        </div>
      )}

      {/* 미체결 내역 탭 */}
      {activeTab === 'PENDING' && (
        <div className="pending-content">
          <div className="pending-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '0.5rem 0' }}>
            <span style={{ color: '#aaa', fontSize: '0.9em' }}>
              전체 종목
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
                    <div className="pending-item-row">
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
                          className="modify-btn"
                          onClick={() => handleOpenModify(order)}
                          disabled={!order.orderId || !!modifying[order.orderId] || !!canceling[order.orderId]}
                          title={!order.orderId ? '시스템에 기록된 주문만 정정 가능' : ''}
                        >
                          정정
                        </button>
                        <button
                          className="cancel-btn"
                          onClick={() => handleCancelOrder(order)}
                          disabled={!order.orderId || !!canceling[order.orderId] || !!modifying[order.orderId]}
                          title={!order.orderId ? '시스템에 기록된 주문만 취소 가능' : ''}
                        >
                          {canceling[order.orderId] ? '취소 중 …' : '취소'}
                        </button>
                      </div>
                    </div>

                    {/* 인라인 정정 폼: 해당 주문 클릭 시 펼쳐짐 */}
                    {modifyingOrder?.orderId === order.orderId && (
                      <div className="modify-form">
                        <div className="modify-inputs">
                          <div className="modify-input-group">
                            <label>단가</label>
                            <input
                              type="number"
                              min={1}
                              value={modifyingOrder.newPrice}
                              onChange={(e) =>
                                setModifyingOrder((prev) => ({ ...prev, newPrice: e.target.value }))
                              }
                            />
                          </div>
                          <div className="modify-input-group">
                            <label>수량</label>
                            <input
                              type="number"
                              min={1}
                              value={modifyingOrder.newQuantity}
                              onChange={(e) =>
                                setModifyingOrder((prev) => ({ ...prev, newQuantity: e.target.value }))
                              }
                            />
                          </div>
                        </div>
                        <div className="modify-actions">
                          <button
                            className="modify-save-btn"
                            onClick={handleSaveModify}
                            disabled={modifying[order.orderId]}
                          >
                            {modifying[order.orderId] ? '정정 중 …' : '저장'}
                          </button>
                          <button
                            className="modify-cancel-btn"
                            onClick={handleCloseModify}
                            disabled={modifying[order.orderId]}
                          >
                            닫기
                          </button>
                        </div>
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </div>
      )}

      {/* 주문 취소 확인 다이얼로그 — window.confirm 대체 */}
      <ConfirmDialog
        open={!!cancelConfirm}
        title="주문 취소"
        message={cancelConfirm ? `주문(${cancelConfirm.orderId})을 취소하시겠습니까?` : ''}
        confirmLabel="주문 취소"
        cancelLabel="닫기"
        variant="danger"
        onConfirm={() => cancelConfirm && executeCancelOrder(cancelConfirm)}
        onClose={() => setCancelConfirm(null)}
      />
    </div>
  );
}