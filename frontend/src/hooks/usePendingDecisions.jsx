import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { toast } from 'sonner';
import { getPendingDecisions } from '../api/aiAgent';
import { useNotifications } from './useNotifications';

/**
 * AI 판단 승인 대기 폴링 + 만료 감지.
 *
 * - 30초 주기로 GET /ai-agent/decisions/pending
 * - 1초 주기로 카운트다운 갱신
 * - 새 pending 발생 시: 토스트 + 알림 목록 추가 (APPROVAL_REQUEST)
 * - 만료 감지(클라이언트 사이드, approvalExpiresAt 도달 시): 토스트 + 알림 (EXPIRED) + 로컬 제거
 *   (다음 폴링에서도 백엔드가 EXPIRED 전환했을 것이므로 자연 동기화)
 * - 승인/거부 후 refresh() 호출하면 즉시 재조회
 */

const POLL_INTERVAL_MS = 30 * 1000;
const COUNTDOWN_INTERVAL_MS = 1000;

const PendingDecisionsContext = createContext({
  pending: [],
  now: Date.now(),
  refresh: () => {},
});

export function PendingDecisionsProvider({ children }) {
  const [pending, setPending] = useState([]);
  const [now, setNow] = useState(Date.now());
  // 처음 본 pending id — 중복 알림 방지
  const seenIdsRef = useRef(new Set());
  // 첫 번째 로드인지 여부 — 새로고침/진입 시 기존 항목에 대한 중복 토스트 경고 방지
  const isFirstLoadRef = useRef(true);
  // 만료 알림 한 번만 — 카운트다운이 반복적으로 0 이하라도 한 번만 처리
  const expiredHandledRef = useRef(new Set());
  const { addNotification, markJudgmentAsRead } = useNotifications();

  const fetchPending = useCallback(async () => {
    try {
      const list = await getPendingDecisions();
      const arr = Array.isArray(list) ? list : [];

      // 새로 들어온 항목 감지 → 토스트 + 알림 추가
      arr.forEach((d) => {
        const id = d.id;
        if (id == null) return;
        if (!seenIdsRef.current.has(id)) {
          seenIdsRef.current.add(id);
          const side = d.decision ?? '판단';
          addNotification({
            type: 'APPROVAL_REQUEST',
            message: `AI 승인 요청: ${d.stockCode ?? '-'}`,
            description: `${side} · 위험도 ${d.riskLevel ?? '-'} · 5분 내 결정 필요`,
            judgmentId: id,
          });
          
          // 처음 화면에 진입/새로고침할 때 이미 대기 중인 항목들에 대한 불필요한 토스트 경고 팝업 차단
          if (!isFirstLoadRef.current) {
            toast.warning('AI 승인 요청', {
              description: `${d.stockCode ?? '-'} · ${side}`,
            });
          }
        }
      });

      // 첫 로드 완료 처리
      isFirstLoadRef.current = false;

      // 목록에서 사라진 항목은 seenIds에서 제거 (다음 발생 시 다시 알림 받게)
      const currentIds = new Set(arr.map((d) => d.id));
      Array.from(seenIdsRef.current).forEach((id) => {
        if (!currentIds.has(id)) {
          seenIdsRef.current.delete(id);
          expiredHandledRef.current.delete(id);
        }
      });

      setPending(arr);
    } catch (error) {
      if (error?.status !== 404) {
        console.warn('AI 승인 대기 조회 실패:', error);
      }
    }
  }, [addNotification]);

  // 초기 + 30초 폴링
  useEffect(() => {
    fetchPending();
    const id = setInterval(fetchPending, POLL_INTERVAL_MS);
    return () => clearInterval(id);
  }, [fetchPending]);

  // 1초 카운트다운 갱신
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), COUNTDOWN_INTERVAL_MS);
    return () => clearInterval(id);
  }, []);

  // 만료 감지 — approvalExpiresAt이 now를 지나면 알림 + 로컬 제거
  useEffect(() => {
    const expiredItems = pending.filter((d) => {
      if (!d.approvalExpiresAt) return false;
      const expiresAt = new Date(d.approvalExpiresAt).getTime();
      return Number.isFinite(expiresAt) && now >= expiresAt && !expiredHandledRef.current.has(d.id);
    });
    if (expiredItems.length === 0) return;

    expiredItems.forEach((d) => {
      expiredHandledRef.current.add(d.id);
      const side = d.decision ?? '판단';
      addNotification({
        type: 'EXPIRED',
        message: `AI 판단 만료: ${d.stockCode ?? '-'}`,
        description: `${side} 승인 시간(5분) 초과로 자동 만료됨`,
        judgmentId: d.id,
      });
      toast.warning('AI 판단 만료', {
        description: `${d.stockCode ?? '-'} · ${side}`,
      });
      // 기존 승인 요청 알림을 읽음 처리
      markJudgmentAsRead(d.id);
    });

    const expiredIds = new Set(expiredItems.map((d) => d.id));
    setPending((prev) => prev.filter((d) => !expiredIds.has(d.id)));
  }, [now, pending, addNotification, markJudgmentAsRead]);

  const refresh = useCallback(() => fetchPending(), [fetchPending]);

  const value = useMemo(() => ({ pending, now, refresh }), [pending, now, refresh]);

  return <PendingDecisionsContext.Provider value={value}>{children}</PendingDecisionsContext.Provider>;
}

export function usePendingDecisions() {
  return useContext(PendingDecisionsContext);
}
