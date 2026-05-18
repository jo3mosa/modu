import { useEffect, useRef, useState, createContext, useContext } from 'react';
import { getSseToken } from '../api/order';

const OrderSSEContext = createContext({ isConnected: false, latestEvent: null, latestAgentMessage: null });

/**
 * SSE 연결 관리 및 이벤트를 전역으로 제공하는 Context Provider
 */
export function OrderSSEProvider({ children }) {
  const eventSourceRef = useRef(null);
  const [isConnected, setIsConnected] = useState(false);
  const [latestEvent, setLatestEvent] = useState(null);
  // AI 에이전트 발화(agent-message 이벤트) — useAgentChat 이 구독해 채널별 버퍼에 push
  const [latestAgentMessage, setLatestAgentMessage] = useState(null);
  const reconnectTimeoutRef = useRef(null);

  useEffect(() => {
    let isMounted = true;

    const connectSSE = async () => {
      try {
        if (eventSourceRef.current) {
          eventSourceRef.current.close();
        }

        const { token } = await getSseToken();
        if (!isMounted) return;

        const sseUrl = `/api/v1/orders/connect?token=${token}`;
        const sse = new EventSource(sseUrl);
        eventSourceRef.current = sse;

        sse.onopen = () => {
          console.log('SSE Connected');
          if (isMounted) setIsConnected(true);
        };

        sse.addEventListener('order', (e) => {
          try {
            const data = JSON.parse(e.data);
            // 의존성 배열 업데이트를 위해 고유 timestamp 부여
            if (isMounted) setLatestEvent({ ...data, _t: Date.now() });
          } catch (error) {
            console.error('SSE Message Parse Error:', error);
          }
        });

        // AI 에이전트 발화 이벤트 — BE 가 ai.agent.message Kafka 토픽 수신 시 본인 연결로 푸시
        sse.addEventListener('agent-message', (e) => {
          try {
            const data = JSON.parse(e.data);
            if (isMounted) setLatestAgentMessage({ ...data, _t: Date.now() });
          } catch (error) {
            console.error('agent-message parse error:', error);
          }
        });

        sse.onerror = (error) => {
          console.error('SSE Connection Error:', error);
          sse.close();
          if (isMounted) setIsConnected(false);

          if (reconnectTimeoutRef.current) clearTimeout(reconnectTimeoutRef.current);
          reconnectTimeoutRef.current = setTimeout(() => {
            if (isMounted) connectSSE();
          }, 5000);
        };
      } catch (err) {
        console.warn('SSE Token Fetch Failed (Backend might not be updated yet):', err.message);
        if (isMounted) setIsConnected(false);
      }
    };

    connectSSE();

    return () => {
      isMounted = false;
      if (reconnectTimeoutRef.current) clearTimeout(reconnectTimeoutRef.current);
      if (eventSourceRef.current) {
        eventSourceRef.current.close();
      }
    };
  }, []);

  return (
    <OrderSSEContext.Provider value={{ isConnected, latestEvent, latestAgentMessage }}>
      {children}
    </OrderSSEContext.Provider>
  );
}

/**
 * 전역 SSE 상태 및 이벤트를 구독하는 훅
 */
export function useOrderSSE() {
  return useContext(OrderSSEContext);
}
