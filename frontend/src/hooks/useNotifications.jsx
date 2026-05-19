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
    if (!raw) return [];
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
