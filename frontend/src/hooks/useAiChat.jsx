import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { toast } from 'sonner';
import { getAiDecisions, getAiDecisionByOrder } from '../api/aiAgent';
import { useOrderSSE } from './useOrderSSE';

/**
 * AI 에이전트 채팅 전역 Context.
 *
 * - 메시지: 한 AI 판단(decision) → 4개 봇 메시지로 변환되어 messages 배열에 추가
 * - 트리거: 초기 진입 시 최근 5건 fetch + SSE ORDER_SUBMITTED 수신 시 해당 주문의 AI 판단 조회
 * - 위치: 플로팅 버튼 위치(localStorage 영속), 채팅창 열림 여부
 * - 알림: 새 메시지 도착 시 sonner 토스트로 알림 (어느 페이지든)
 */

// 4봇 페르소나 정의 — UI에서 참조
export const BOT_PROFILES = {
  analyst: { name: '분석봇', icon: '🔍', color: '#3b82f6' },
  news:    { name: '뉴스봇', icon: '📰', color: '#84cc16' },
  reason:  { name: '추론봇', icon: '🤔', color: '#a78bfa' },
  decide:  { name: '결정봇', icon: '🎯', color: '#ef4444' },
};

const MESSAGE_STORAGE_KEY = 'modu.aiChatMessages';
const POSITION_STORAGE_KEY = 'modu.aiChatButtonPos';
const MAX_MESSAGES = 80; // 한 봇당 ~20개 판단(20 × 4 = 80) 보관

// indicators_snapshot 에서 기술지표/뉴스 분리 — 키 이름이 케이스별로 달라 generic 매핑
const TECHNICAL_KEYS = ['rsi', 'macd', 'ma5', 'ma20', 'ma60', 'bollinger', 'bb', 'volume', 'trend'];
const NEWS_KEYS = ['news', 'sentiment', 'event', 'disclosure'];

function pickByKeys(obj, keyHints) {
  if (!obj || typeof obj !== 'object') return null;
  const lower = (s) => String(s).toLowerCase();
  const matched = Object.entries(obj).filter(([k]) =>
    keyHints.some((h) => lower(k).includes(h))
  );
  if (matched.length === 0) return null;
  return matched
    .map(([k, v]) => {
      if (v == null) return null;
      if (typeof v === 'object') return `${k}: ${JSON.stringify(v)}`;
      return `${k}: ${v}`;
    })
    .filter(Boolean)
    .join(' · ');
}

/**
 * 한 AI 판단 → 4개 봇 메시지로 변환.
 * judgmentReason 텍스트를 문장 단위로 절반씩 추론봇/결정봇에 분배.
 * indicators 가 비면 봇 메시지를 생략(빈 채팅 방지).
 */
function decisionToMessages(decision) {
  if (!decision?.id) return [];
  const id = decision.id;
  const stockCode = decision.stockCode ?? '-';
  const decidedAt = decision.decidedAt ?? new Date().toISOString();
  const confidence = decision.confidence ?? null;
  const action = decision.action ?? 'UNKNOWN';
  const reason = (decision.reason ?? '').trim();
  const indicators = decision.indicatorsSnapshot ?? {};

  const technical = pickByKeys(indicators, TECHNICAL_KEYS);
  const news = pickByKeys(indicators, NEWS_KEYS);

  // 문장 분리 — 마침표/물음표/느낌표 기준. 비어있으면 reason 통째로 결정봇이 받음
  const sentences = reason.split(/(?<=[.!?])\s+/).map((s) => s.trim()).filter(Boolean);
  const half = Math.max(1, Math.ceil(sentences.length / 2));
  const firstHalf = sentences.slice(0, half).join(' ');
  const secondHalf = sentences.slice(half).join(' ');

  const actionLabel = action === 'BUY' ? '매수' : action === 'SELL' ? '매도' : action === 'HOLD' ? '관망' : '판단';
  const confidenceLabel = confidence != null ? `신뢰도 ${confidence}% · ` : '';
  const decideTail = secondHalf || (firstHalf ? '' : reason);

  return [
    technical && {
      id: `${id}-analyst`,
      decisionId: id,
      stockCode,
      bot: 'analyst',
      text: technical,
      timestamp: decidedAt,
    },
    news && {
      id: `${id}-news`,
      decisionId: id,
      stockCode,
      bot: 'news',
      text: news,
      timestamp: decidedAt,
    },
    firstHalf && {
      id: `${id}-reason`,
      decisionId: id,
      stockCode,
      bot: 'reason',
      text: firstHalf,
      timestamp: decidedAt,
    },
    {
      id: `${id}-decide`,
      decisionId: id,
      stockCode,
      bot: 'decide',
      text: `${confidenceLabel}${actionLabel} 결정${decideTail ? ` — ${decideTail}` : ''}`,
      timestamp: decidedAt,
    },
  ].filter(Boolean);
}

// localStorage 안전 헬퍼 (SSR/이상값 방어)
function loadFromStorage(key, fallback) {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return fallback;
    return JSON.parse(raw);
  } catch {
    return fallback;
  }
}

function saveToStorage(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // quota 초과 등 — 조용히 무시
  }
}

const AiChatContext = createContext({
  messages: [],
  isOpen: false,
  unreadCount: 0,
  buttonPosition: null,
  openChat: () => {},
  closeChat: () => {},
  toggleChat: () => {},
  setButtonPosition: () => {},
});

export function AiChatProvider({ children }) {
  const [messages, setMessages] = useState(() => loadFromStorage(MESSAGE_STORAGE_KEY, []));
  const [isOpen, setIsOpen] = useState(false);
  const [unreadCount, setUnreadCount] = useState(0);
  const [buttonPosition, setButtonPositionState] = useState(() =>
    loadFromStorage(POSITION_STORAGE_KEY, null)
  );

  // 중복 추가 방지용 — 본 판단 id set
  const seenDecisionIdsRef = useRef(new Set(messages.map((m) => m.decisionId)));

  const { latestEvent } = useOrderSSE();

  // messages 변경 시 localStorage 저장 + 최대 N개 유지
  useEffect(() => {
    saveToStorage(MESSAGE_STORAGE_KEY, messages.slice(-MAX_MESSAGES));
  }, [messages]);

  // 판단을 메시지로 변환해 추가 (중복 방지 + 알림)
  const addDecision = useCallback((decision, { notify = true } = {}) => {
    if (!decision?.id) return;
    if (seenDecisionIdsRef.current.has(decision.id)) return;
    seenDecisionIdsRef.current.add(decision.id);

    const newMessages = decisionToMessages(decision);
    if (newMessages.length === 0) return;

    setMessages((prev) => [...prev, ...newMessages].slice(-MAX_MESSAGES));

    if (notify) {
      setUnreadCount((c) => c + 1);
      toast.message(`AI가 새 판단을 내렸습니다`, {
        description: `${decision.stockCode} · ${BOT_PROFILES.decide.icon} ${BOT_PROFILES.decide.name}`,
        action: {
          label: '보기',
          onClick: () => {
            setIsOpen(true);
            setUnreadCount(0);
          },
        },
      });
    }
  }, []);

  // 초기 진입 시 최근 AI 판단 5건 로드 (알림 없이)
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const data = await getAiDecisions({ page: 0, size: 5 });
        if (cancelled) return;
        const decisions = data?.content ?? [];
        // 시간 오름차순으로 추가해 채팅 순서가 자연스럽게 흐르도록
        [...decisions].reverse().forEach((d) => addDecision(d, { notify: false }));
      } catch (error) {
        if (error?.status !== 404) {
          console.warn('AI 채팅 초기 로드 실패:', error);
        }
      }
    })();
    return () => { cancelled = true; };
  }, [addDecision]);

  // SSE ORDER_SUBMITTED 수신 시 해당 주문의 AI 판단 조회 (404면 무시 — 수동 주문 등)
  useEffect(() => {
    if (!latestEvent || latestEvent.type !== 'ORDER_SUBMITTED') return;
    const orderId = latestEvent.orderId;
    if (!orderId) return;
    (async () => {
      try {
        const detail = await getAiDecisionByOrder(orderId);
        addDecision(detail, { notify: true });
      } catch (error) {
        // AI 판단이 연결되지 않은 주문(수동/외부)이면 404 — 조용히 무시
        if (error?.status !== 404 && error?.errorCode !== 'AI_001') {
          console.warn('AI 판단 조회 실패:', error);
        }
      }
    })();
  }, [latestEvent, addDecision]);

  const openChat = useCallback(() => {
    setIsOpen(true);
    setUnreadCount(0);
  }, []);

  const closeChat = useCallback(() => setIsOpen(false), []);

  const toggleChat = useCallback(() => {
    setIsOpen((prev) => {
      const next = !prev;
      if (next) setUnreadCount(0);
      return next;
    });
  }, []);

  const setButtonPosition = useCallback((pos) => {
    setButtonPositionState(pos);
    saveToStorage(POSITION_STORAGE_KEY, pos);
  }, []);

  const value = useMemo(
    () => ({ messages, isOpen, unreadCount, buttonPosition, openChat, closeChat, toggleChat, setButtonPosition }),
    [messages, isOpen, unreadCount, buttonPosition, openChat, closeChat, toggleChat, setButtonPosition]
  );

  return <AiChatContext.Provider value={value}>{children}</AiChatContext.Provider>;
}

export function useAiChat() {
  return useContext(AiChatContext);
}
