import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { toast } from 'sonner';
import { getAgentMessages } from '../api/aiAgent';
import { useOrderSSE } from './useOrderSSE';

/**
 * AI 에이전트 회의 채팅 — per-stock 메시지 버퍼 + 실시간 SSE 수신
 *
 * [흐름]
 *  - 채널 진입(loadHistory) → REST 로 최신 50개 fetch
 *  - 위로 스크롤(loadMore) → REST 복합 커서로 다음 페이지 append
 *  - SSE 'agent-message' 수신 → 해당 종목 버퍼 맨 앞에 prepend + unread 증가
 *
 * [DB 와의 관계]
 *  - 정렬: createdAt DESC, id DESC (DB 와 일치)
 *  - 화면 표시 시 reverse() 해서 시간순(오래된 → 최신)으로
 *  - 영속화는 서버 책임 — 본 훅은 메모리 버퍼만 유지 (localStorage 사용 X)
 */

// BE 의 AgentType enum 과 동일 키 (대문자)
// useAiChat 의 소문자 키(bull/bear/strategy/decide) 와는 별개
export const BOT_PROFILES = {
  BULL:     { name: '강세 리서처',    role: '리서치',   icon: '강세', color: '#ef4444' },
  BEAR:     { name: '약세 리서처',    role: '리서치',   icon: '약세', color: '#3b82f6' },
  STRATEGY: { name: '전략 매니저',    role: '전략',     icon: '전략', color: '#a78bfa' },
  DECIDE:   { name: '의사결정 매니저', role: '의사결정', icon: '결정', color: '#10b981' },
};

const DEFAULT_PAGE_SIZE = 50;

function emptyChannel() {
  return {
    messages: [],          // createdAt DESC 정렬
    nextCursor: null,
    nextCursorId: null,
    hasMore: false,
    loaded: false,         // false | 'pending' | true
  };
}

const AgentChatContext = createContext({
  byStock: {},
  unreadByStock: {},
  loadHistory: async () => {},
  loadMore: async () => {},
  markRead: () => {},
});

export function AgentChatProvider({ children }) {
  // 채널별 메시지 버퍼 — { [stockCode]: ChannelState }
  const [byStock, setByStock] = useState({});
  // 채널별 미확인 메시지 수
  const [unreadByStock, setUnreadByStock] = useState({});
  // 중복 prepend 방지 — SSE 가 같은 messageId 를 두 번 보내거나, REST 가 SSE 와 겹칠 때 안전장치
  const seenIdsRef = useRef(new Set());

  const { latestAgentMessage } = useOrderSSE();

  // ── 1) SSE 수신 → 해당 종목 버퍼에 prepend ─────────────────────────────
  useEffect(() => {
    if (!latestAgentMessage) return;
    const msg = latestAgentMessage;
    if (msg.messageId == null) return;
    if (seenIdsRef.current.has(msg.messageId)) return;
    seenIdsRef.current.add(msg.messageId);

    setByStock((prev) => {
      const channel = prev[msg.stockCode] ?? emptyChannel();
      return {
        ...prev,
        [msg.stockCode]: {
          ...channel,
          messages: [msg, ...channel.messages],
        },
      };
    });

    setUnreadByStock((prev) => ({
      ...prev,
      [msg.stockCode]: (prev[msg.stockCode] ?? 0) + 1,
    }));

    // 토스트 알림 — 어느 페이지에 있어도 받음
    const bot = BOT_PROFILES[msg.agent];
    if (bot) {
      toast.message('AI 에이전트가 발언했어요', {
        description: `${msg.stockCode} · ${bot.name}`,
      });
    }
  }, [latestAgentMessage]);

  // ── 2) 채널 진입 시 첫 페이지 fetch (이미 로드됐으면 skip) ──────────────
  const loadHistory = useCallback(async (stockCode) => {
    if (!stockCode) return;

    // 동시 진입 race 방지 — 'pending' 마킹 후 fetch
    let shouldFetch = false;
    setByStock((prev) => {
      const channel = prev[stockCode];
      if (channel?.loaded) return prev;
      shouldFetch = true;
      return { ...prev, [stockCode]: { ...emptyChannel(), loaded: 'pending' } };
    });
    if (!shouldFetch) return;

    try {
      const res = await getAgentMessages({ stockCode, size: DEFAULT_PAGE_SIZE });
      res.content.forEach((m) => seenIdsRef.current.add(m.messageId));

      setByStock((prev) => ({
        ...prev,
        [stockCode]: {
          messages: res.content,
          nextCursor: res.nextCursor,
          nextCursorId: res.nextCursorId,
          hasMore: res.hasMore,
          loaded: true,
        },
      }));
    } catch (error) {
      console.error('에이전트 메시지 히스토리 로드 실패:', error);
      // 재시도 가능하도록 loaded 플래그 초기화
      setByStock((prev) => ({
        ...prev,
        [stockCode]: { ...emptyChannel(), loaded: false },
      }));
    }
  }, []);

  // ── 3) 위로 스크롤 → 다음 페이지 append (과거 방향) ────────────────────
  const loadMore = useCallback(async (stockCode) => {
    if (!stockCode) return;
    const channel = byStock[stockCode];
    if (!channel || channel.loaded !== true) return;
    if (!channel.hasMore || channel.nextCursor == null) return;

    try {
      const res = await getAgentMessages({
        stockCode,
        before: channel.nextCursor,
        beforeId: channel.nextCursorId,
        size: DEFAULT_PAGE_SIZE,
      });
      res.content.forEach((m) => seenIdsRef.current.add(m.messageId));

      setByStock((prev) => {
        const current = prev[stockCode];
        if (!current) return prev;
        return {
          ...prev,
          [stockCode]: {
            ...current,
            // 과거 메시지를 뒤에 append (DESC 정렬 유지)
            messages: [...current.messages, ...res.content],
            nextCursor: res.nextCursor,
            nextCursorId: res.nextCursorId,
            hasMore: res.hasMore,
          },
        };
      });
    } catch (error) {
      console.error('에이전트 메시지 추가 페이지 로드 실패:', error);
    }
  }, [byStock]);

  // ── 4) 채널 선택 시 unread 0 으로 ──────────────────────────────────────
  const markRead = useCallback((stockCode) => {
    if (!stockCode) return;
    setUnreadByStock((prev) => {
      if (!prev[stockCode]) return prev;
      return { ...prev, [stockCode]: 0 };
    });
  }, []);

  const value = useMemo(
    () => ({ byStock, unreadByStock, loadHistory, loadMore, markRead }),
    [byStock, unreadByStock, loadHistory, loadMore, markRead],
  );

  return <AgentChatContext.Provider value={value}>{children}</AgentChatContext.Provider>;
}

export function useAgentChat() {
  return useContext(AgentChatContext);
}
