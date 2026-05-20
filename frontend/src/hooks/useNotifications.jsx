import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';

/**
 * 전역 알림 Context.
 *
 * - 모든 토스트성 알림(체결/만료/Kill Switch/AI 승인/승인/거부)을 한 곳에 누적
 * - 24시간 지난 알림은 자동 제거 (1분 간격 sweep)
 * - localStorage 영속 — 새로고침해도 유지
 *
 * 알림 타입:
 *  EXECUTED         : 주문 체결 완료
 *  EXPIRED          : AI 판단 5분 초과 만료
 *  KILL_SWITCH      : 자동매매 강제 중단 (KIS 거부 5회 누적 등)
 *  APPROVAL_REQUEST : AI 판단 사용자 승인 대기 발생
 *  APPROVED         : 사용자가 AI 판단 승인
 *  REJECTED         : 사용자가 AI 판단 거부
 */

const STORAGE_KEY = 'modu.notifications';
const RETENTION_MS = 24 * 60 * 60 * 1000; // 24시간
const SWEEP_INTERVAL_MS = 60 * 1000;      // 1분 간격 만료 정리
const MAX_ITEMS = 100;

export const NOTIFICATION_TYPE_META = {
  EXECUTED: { label: '체결', color: '#84cc16' },
  EXPIRED: { label: '만료', color: '#888' },
  KILL_SWITCH: { label: 'Kill Switch', color: '#ef4444' },
  APPROVAL_REQUEST: { label: '승인 요청', color: '#eab308' },
  APPROVED: { label: '승인', color: '#84cc16' },
  REJECTED: { label: '거부', color: '#888' },
};

function loadFromStorage() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw || raw === '[]') {
      // 로컬 스토리지에 데이터가 없는 경우, 테스트용 고품격 더미 알림 데이터를 삽입합니다.
      const initialDummies = [
        {
          id: `dummy-executed-1`,
          type: 'EXECUTED',
          message: '삼성전자 1주 매수 체결 완료',
          description: '체결가: 78,500원 · 총액: 78,500원',
          timestamp: new Date(Date.now() - 5 * 60 * 1000).toISOString(), // 5분 전
          isRead: false,
        },
        {
          id: `dummy-app-req-1`,
          type: 'APPROVAL_REQUEST',
          message: 'SK하이닉스 매도 승인 대기',
          description: 'AI 분석: 최근 최고점 접근으로 차익 실현 추천',
          timestamp: new Date(Date.now() - 15 * 60 * 1000).toISOString(), // 15분 전
          isRead: false,
        },
        {
          id: `dummy-kill-1`,
          type: 'KILL_SWITCH',
          message: '자동매매 긴급 강제 중단 발동',
          description: '안전 장치: 최대 손실 한도 초과로 인한 자동매매 중단',
          timestamp: new Date(Date.now() - 45 * 60 * 1000).toISOString(), // 45분 전
          isRead: false,
        },
        {
          id: `dummy-expired-1`,
          type: 'EXPIRED',
          message: '카카오 매수 판단 시간 만료',
          description: '5분 초과: 시장 변동으로 인한 자동 주문 취소',
          timestamp: new Date(Date.now() - 120 * 60 * 1000).toISOString(), // 2시간 전
          isRead: true,
        }
      ];
      saveToStorage(initialDummies);
      return initialDummies;
    }
    const items = JSON.parse(raw);
    if (!Array.isArray(items)) return [];
    const now = Date.now();
    return items.filter((n) => {
      // 로컬 스토리지에 남은 임시 더미 매매 알림(ID 또는 judgmentId가 9999로 시작) 원천 제거
      if (
        n.judgmentId === 99991 ||
        n.judgmentId === 99992 ||
        String(n.judgmentId).startsWith('9999') ||
        String(n.id).startsWith('9999')
      ) {
        return false;
      }
      const ts = new Date(n.timestamp).getTime();
      return Number.isFinite(ts) && now - ts < RETENTION_MS;
    });
  } catch {
    return [];
  }
}

function saveToStorage(items) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(items));
  } catch {
    // quota 초과 등 — 무시
  }
}

const NotificationsContext = createContext({
  notifications: [],
  unreadCount: 0,
  addNotification: () => { },
  markAsRead: () => { },
  markAllAsRead: () => { },
  removeNotification: () => { },
  clearAll: () => { },
});

export function NotificationsProvider({ children }) {
  const [notifications, setNotifications] = useState(loadFromStorage);

  // 변경 시 localStorage 저장 (최대 N개로 자름)
  useEffect(() => {
    saveToStorage(notifications.slice(0, MAX_ITEMS));
  }, [notifications]);

  // 1분 간격으로 24시간 지난 항목 제거
  useEffect(() => {
    const sweep = () => {
      setNotifications((prev) => {
        const now = Date.now();
        const filtered = prev.filter((n) => {
          const ts = new Date(n.timestamp).getTime();
          return Number.isFinite(ts) && now - ts < RETENTION_MS;
        });
        return filtered.length === prev.length ? prev : filtered;
      });
    };
    const id = setInterval(sweep, SWEEP_INTERVAL_MS);
    return () => clearInterval(id);
  }, []);

  const addNotification = useCallback(({ type, message, description, actionUrl, judgmentId }) => {
    const item = {
      id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      type,
      message,
      description: description ?? null,
      actionUrl: actionUrl ?? null,
      judgmentId: judgmentId ?? null,
      timestamp: new Date().toISOString(),
      isRead: false,
    };
    setNotifications((prev) => {
      // 중복 방지: 동일한 judgmentId와 type을 가진 알림이 이미 존재하면 추가하지 않음
      if (judgmentId && prev.some((n) => n.judgmentId === judgmentId && n.type === type)) {
        return prev;
      }
      return [item, ...prev].slice(0, MAX_ITEMS);
    });
    return item;
  }, []);

  const markAsRead = useCallback((id) => {
    setNotifications((prev) => prev.map((n) => (n.id === id ? { ...n, isRead: true } : n)));
  }, []);

  const markAllAsRead = useCallback(() => {
    setNotifications((prev) => prev.map((n) => ({ ...n, isRead: true })));
  }, []);

  const removeNotification = useCallback((id) => {
    setNotifications((prev) => prev.filter((n) => n.id !== id));
  }, []);

  const clearAll = useCallback(() => setNotifications([]), []);

  const markJudgmentAsRead = useCallback((judgmentId) => {
    if (!judgmentId) return;
    setNotifications((prev) =>
      prev.map((n) =>
        n.judgmentId === judgmentId && n.type === 'APPROVAL_REQUEST'
          ? { ...n, isRead: true }
          : n
      )
    );
  }, []);

  const unreadCount = useMemo(
    () => notifications.filter((n) => !n.isRead).length,
    [notifications]
  );

  const value = useMemo(
    () => ({
      notifications,
      unreadCount,
      addNotification,
      markAsRead,
      markAllAsRead,
      removeNotification,
      clearAll,
      markJudgmentAsRead,
    }),
    [
      notifications,
      unreadCount,
      addNotification,
      markAsRead,
      markAllAsRead,
      removeNotification,
      clearAll,
      markJudgmentAsRead,
    ]
  );

  return <NotificationsContext.Provider value={value}>{children}</NotificationsContext.Provider>;
}

export function useNotifications() {
  return useContext(NotificationsContext);
}
