import { useEffect, useRef, useState } from 'react';
import { getSseToken } from '../api/order';

/**
 * SSE 연결 관리 + 이벤트 콜백으로 전달하는 커스텀 훅
 *
 * @param {{
 *   onOrderEvent: (event: { type: string, orderId: string, status: string, message: string }) => void
 * }} params
 */
export function useOrderSSE({ onOrderEvent }) {
  const eventSourceRef = useRef(null);
  const [isConnected, setIsConnected] = useState(false);
  const reconnectTimeoutRef = useRef(null);

  useEffect(() => {
    // 마운트 시 연결 시도
    let isMounted = true;

    const connectSSE = async () => {
      try {
        if (eventSourceRef.current) {
          eventSourceRef.current.close();
        }

        // 1. 단기 토큰 발급
        const { token } = await getSseToken();
        if (!isMounted) return;

        // 2. EventSource 연결
        // api proxy가 '/api'로 시작하므로 경로 맞추기
        const sseUrl = `/api/v1/orders/connect?token=${token}`;
        const sse = new EventSource(sseUrl);
        eventSourceRef.current = sse;

        sse.onopen = () => {
          console.log('SSE Connected');
          if (isMounted) setIsConnected(true);
        };

        // 3. 'order' 이벤트 리스너 등록
        sse.addEventListener('order', (e) => {
          try {
            const data = JSON.parse(e.data);
            if (onOrderEvent) {
              onOrderEvent(data);
            }
          } catch (error) {
            console.error('SSE Message Parse Error:', error);
          }
        });

        // 4. 에러 시 재연결 시도
        sse.onerror = (error) => {
          console.error('SSE Connection Error:', error);
          sse.close();
          if (isMounted) setIsConnected(false);

          // 토큰 만료 또는 네트워크 끊김 -> 5초 후 재연결 시도
          if (reconnectTimeoutRef.current) clearTimeout(reconnectTimeoutRef.current);
          reconnectTimeoutRef.current = setTimeout(() => {
            if (isMounted) connectSSE();
          }, 5000);
        };
      } catch (err) {
        // 404 에러 (하위 호환 모드 유지 위해 콘솔만 출력)
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
  }, [onOrderEvent]);

  return { isConnected };
}
