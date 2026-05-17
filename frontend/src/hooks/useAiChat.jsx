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

// 4봇 페르소나 — 카톡 협업 메타포 (직장 동료 톤)
// role: 화면에 이름 옆 작게 표시되는 직책
export const BOT_PROFILES = {
  bull:     { name: '강세 리서처', role: '리서치', icon: '강세', color: '#ef4444' },
  bear:     { name: '약세 리서처', role: '리서치', icon: '약세', color: '#3b82f6' },
  strategy: { name: '전략 매니저', role: '전략', icon: '전략', color: '#a78bfa' },
  decide:   { name: '의사결정 매니저', role: '의사결정', icon: '결정', color: '#10b981' },
};

// indicators_snapshot 키 한국어 라벨 — 채팅으로 자연스럽게 표시
const TECHNICAL_KEY_LABELS = {
  rsi: 'RSI', macd: 'MACD', ma5: '5일선', ma20: '20일선', ma60: '60일선',
  bollinger: '볼린저밴드', bb: '볼린저밴드', volume: '거래량', trend: '추세',
};
const NEWS_KEY_LABELS = {
  news_sentiment: '뉴스 분위기', news_count: '뉴스', sentiment: '감성',
  event: '이벤트', disclosure: '공시',
};

// 봇별 말투 — 백엔드 LLM 결과("~합니다") → 카톡 친구체("~이에요")
function applyPersonaTone(text, bot) {
  if (!text) return text;
  let out = text
    .replace(/입니다\.?/g, '이에요')
    .replace(/합니다\.?/g, '해요')
    .replace(/됩니다\.?/g, '돼요')
    .replace(/보입니다\.?/g, '보여요')
    .replace(/있습니다\.?/g, '있어요')
    .replace(/없습니다\.?/g, '없어요')
    .replace(/추천합니다\.?/g, '추천해요')
    .replace(/유지되고 있어요/g, '잘 가고 있어요');
  const prefix = {
    bull:     '긍정적인 시그널이 포착되었습니다.',
    bear:     '리스크 요인이 관찰됩니다.',
    strategy: '현 시점 전략 제안 드립니다.',
    decide:   '최종 결정 내역입니다.',
  }[bot] ?? '';
  return prefix ? `${prefix} ${out}` : out;
}

const MESSAGE_STORAGE_KEY = 'modu.aiChatMessages';
const POSITION_STORAGE_KEY = 'modu.aiChatButtonPos';
const MAX_MESSAGES = 80; // 한 봇당 ~20개 판단(20 × 4 = 80) 보관

// 시연용 mock — 백엔드 데이터 0건일 때 자동 주입. 운영에서 끄고 싶으면 false로.
const ENABLE_DEMO_MOCK = false;

export const DEMO_DECISIONS = [
  {
    id: 'demo-1',
    stockCode: '005930',
    action: 'BUY',
    confidence: 78,
    decidedAt: new Date(Date.now() - 1000 * 60 * 18).toISOString(),
    reason: '5일선이 20일선 위에서 잘 가고 있네요. 외국인 순매수가 3일 연속 이어지면서 수급 분위기가 좋아요. 분할 매수로 가는 게 안전할 것 같아요.',
    newsSummary: '외국인 순매수 3일 연속 유입 및 반도체 업황 개선 기대감 확산',
    technicalSummary: '5일선이 20일선 상회하며 단기 상승 추세 진입',
    indicatorsSnapshot: {
      rsi: 58,
      macd: '골든크로스 임박',
      ma20: '상회',
      news_sentiment: 0.62,
      news_count: 4,
    },
  },
  {
    id: 'demo-2',
    stockCode: '035720',
    action: 'HOLD',
    confidence: 64,
    decidedAt: new Date(Date.now() - 1000 * 60 * 9).toISOString(),
    reason: 'RSI가 71까지 올라와서 과매수 직전이에요. 차익실현 압력이 슬슬 누적되는 중. 일단 60일선 지지 확인하고 들어가는 게 안전해요.',
    newsSummary: '특징적인 대형 공시나 뉴스 없음',
    technicalSummary: 'RSI 71 도달 및 볼린저 밴드 상단 터치로 단기 과열 양상',
    indicatorsSnapshot: {
      rsi: 71,
      bollinger: '상단 터치',
      volume: '평균 대비 +35%',
      event: '대형 공시 없음',
    },
  },
  {
    id: 'demo-3',
    stockCode: '012450',
    action: 'SELL',
    confidence: 82,
    decidedAt: new Date(Date.now() - 1000 * 60 * 2).toISOString(),
    reason: 'MACD 데드크로스 떴고 거래량도 평균의 2배로 튀었어요. 최근 부정적 뉴스 2건도 같이 잡혔구요. 보유분의 절반 정도는 익절하는 게 좋겠어요.',
    newsSummary: '수익성 악화 우려 및 증권사 목표가 하향 리포트 발간',
    technicalSummary: 'MACD 데드크로스 발생 및 거래량 급증하며 하락 추세 전환 가능성',
    indicatorsSnapshot: {
      rsi: 45,
      macd: '데드크로스',
      volume: '2배 급증',
      news_sentiment: -0.41,
      disclosure: '실적 컨센서스 하회',
    },
  },
];

// indicators_snapshot 에서 기술지표/뉴스 분리 — 키 이름이 케이스별로 달라 generic 매핑
const TECHNICAL_KEYS = ['rsi', 'macd', 'ma5', 'ma20', 'ma60', 'bollinger', 'bb', 'volume', 'trend'];
const NEWS_KEYS = ['news', 'sentiment', 'event', 'disclosure'];

function pickByKeys(obj, keyHints, labelMap = {}) {
  if (!obj || typeof obj !== 'object') return null;
  const lower = (s) => String(s).toLowerCase();
  const matched = Object.entries(obj).filter(([k]) =>
    keyHints.some((h) => lower(k).includes(h))
  );
  if (matched.length === 0) return null;
  return matched
    .map(([k, v]) => {
      if (v == null) return null;
      const label = labelMap[lower(k)] ?? k;
      if (typeof v === 'object') return `${label} ${JSON.stringify(v)}`;
      return `${label} ${v}`;
    })
    .filter(Boolean)
    .join(' · ');
}

/**
 * 한 AI 판단 → 4개 봇 메시지로 변환.
 * judgmentReason 텍스트를 문장 단위로 절반씩 추론봇/결정봇에 분배.
 * indicators 가 비면 봇 메시지를 생략(빈 채팅 방지).
 */
export function decisionToMessages(decision) {
  if (!decision?.id) return [];
  const id = decision.id;
  const stockCode = decision.stockCode ?? '-';
  const decidedAt = decision.decidedAt ?? new Date().toISOString();
  const confidence = decision.confidence ?? null;
  const action = decision.action ?? 'UNKNOWN';
  const reason = (decision.reason ?? '').trim();
  const indicators = decision.indicatorsSnapshot ?? {};

  const technical = pickByKeys(indicators, TECHNICAL_KEYS, TECHNICAL_KEY_LABELS);
  const news = pickByKeys(indicators, NEWS_KEYS, NEWS_KEY_LABELS);

  // 문장 분리 — 마침표/물음표/느낌표 기준. 비어있으면 reason 통째로 결정봇이 받음
  const sentences = reason.split(/(?<=[.!?])\s+/).map((s) => s.trim()).filter(Boolean);
  const half = Math.max(1, Math.ceil(sentences.length / 2));
  const firstHalf = sentences.slice(0, half).join(' ');
  const secondHalf = sentences.slice(half).join(' ');

  const actionLabel = action === 'BUY' ? '매수' : action === 'SELL' ? '매도' : action === 'HOLD' ? '관망' : '판단';
  const confidenceLabel = confidence != null ? `(신뢰도 ${confidence}%) ` : '';
  const decideTail = secondHalf || (firstHalf ? '' : reason);
  const decideText = `${confidenceLabel}${actionLabel} 의견입니다${decideTail ? `. ${applyPersonaTone(decideTail, 'decide').replace(/^최종 결정 내역입니다.\s*/, '')}` : '.'}`;

  const isBullish = action === 'BUY';
  const technicalBot = isBullish ? 'bull' : 'bear';

  // 뉴스나 기술 지표 데이터를 전략 매니저나 리서처가 나눠서 브리핑하도록 병합
  const combinedIndicators = [news, technical].filter(Boolean).join(' / ');

  return [
    combinedIndicators && {
      id: `${id}-researcher`,
      decisionId: id,
      stockCode,
      bot: technicalBot,
      text: applyPersonaTone(combinedIndicators, technicalBot),
      timestamp: decidedAt,
    },
    firstHalf && {
      id: `${id}-strategy`,
      decisionId: id,
      stockCode,
      bot: 'strategy',
      text: applyPersonaTone(firstHalf, 'strategy'),
      timestamp: decidedAt,
    },
    {
      id: `${id}-decide`,
      decisionId: id,
      stockCode,
      bot: 'decide',
      text: decideText,
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

// 메시지 사이 타이핑 인디케이터/등장 간격 (ms)
const TYPING_DURATION = 450;
const BETWEEN_MESSAGES = 180;

const AiChatContext = createContext({
  messages: [],
  isOpen: false,
  unreadCount: 0,
  buttonPosition: null,
  typingBot: null,
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
  // 현재 "입력 중..." 표시 중인 봇 key (analyst | news | reason | decide | null)
  const [typingBot, setTypingBot] = useState(null);

  // 중복 추가 방지용 — 본 판단 id set
  const seenDecisionIdsRef = useRef(new Set(messages.map((m) => m.decisionId)));
  // 진행 중 staggered 타이머 — 컴포넌트 unmount 시 정리
  const staggerTimersRef = useRef([]);

  const { latestEvent } = useOrderSSE();

  // messages 변경 시 localStorage 저장 + 최대 N개 유지
  useEffect(() => {
    saveToStorage(MESSAGE_STORAGE_KEY, messages.slice(-MAX_MESSAGES));
  }, [messages]);

  // unmount 시 타이머 정리
  useEffect(() => () => {
    staggerTimersRef.current.forEach(clearTimeout);
    staggerTimersRef.current = [];
  }, []);

  // 판단을 메시지로 변환해 추가 (중복 방지 + 알림)
  // staggered=true: 봇별로 "입력 중..." 보여주며 순차 등장 (실시간 토론 느낌)
  // staggered=false: 즉시 일괄 추가 (초기 로드용)
  const addDecision = useCallback((decision, { notify = true, staggered = false } = {}) => {
    if (!decision?.id) return;
    if (seenDecisionIdsRef.current.has(decision.id)) return;
    seenDecisionIdsRef.current.add(decision.id);

    const newMessages = decisionToMessages(decision);
    if (newMessages.length === 0) return;

    if (!staggered) {
      setMessages((prev) => [...prev, ...newMessages].slice(-MAX_MESSAGES));
    } else {
      // 순차 등장: typingBot 활성화 → TYPING_DURATION 후 메시지 push → BETWEEN_MESSAGES 후 다음
      let acc = 0;
      newMessages.forEach((msg, idx) => {
        // 1) 타이핑 인디케이터 ON
        const typingTimer = setTimeout(() => setTypingBot(msg.bot), acc);
        staggerTimersRef.current.push(typingTimer);
        acc += TYPING_DURATION;
        // 2) 메시지 push + 타이핑 OFF
        const pushTimer = setTimeout(() => {
          setMessages((prev) => [...prev, msg].slice(-MAX_MESSAGES));
          // 마지막 메시지면 타이핑 종료
          if (idx === newMessages.length - 1) setTypingBot(null);
        }, acc);
        staggerTimersRef.current.push(pushTimer);
        acc += BETWEEN_MESSAGES;
      });
    }

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
  // 응답이 비고 ENABLE_DEMO_MOCK이 켜져 있으면 시연용 mock 주입
  useEffect(() => {
    let cancelled = false;
    (async () => {
      let decisions = [];
      try {
        const data = await getAiDecisions({ page: 0, size: 5 });
        decisions = data?.content ?? [];
      } catch (error) {
        if (error?.status !== 404) {
          console.warn('AI 채팅 초기 로드 실패:', error);
        }
      }
      if (cancelled) return;

      if (decisions.length === 0 && ENABLE_DEMO_MOCK) {
        // localStorage에 mock이 이미 보관되어 있으면(이전 세션) 재주입 안 함
        const hasStoredDemo = messages.some((m) => String(m.decisionId).startsWith('demo-'));
        if (!hasStoredDemo) {
          DEMO_DECISIONS.forEach((d) => addDecision(d, { notify: false }));
        }
      } else {
        // 시간 오름차순으로 추가해 채팅 순서가 자연스럽게 흐르도록
        [...decisions].reverse().forEach((d) => addDecision(d, { notify: false }));
      }
    })();
    return () => { cancelled = true; };
    // messages 의존성에 넣으면 add 마다 재실행되어 무한루프 — 의도적으로 제외
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [addDecision]);

  // SSE ORDER_SUBMITTED 수신 시 해당 주문의 AI 판단 조회 (404면 무시 — 수동 주문 등)
  useEffect(() => {
    if (!latestEvent || latestEvent.type !== 'ORDER_SUBMITTED') return;
    const orderId = latestEvent.orderId;
    if (!orderId) return;
    (async () => {
      try {
        const detail = await getAiDecisionByOrder(orderId);
        // 실시간 토론 느낌 — 봇별로 타이핑 → 순차 등장
        addDecision(detail, { notify: true, staggered: true });
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
    () => ({ messages, isOpen, unreadCount, buttonPosition, typingBot, openChat, closeChat, toggleChat, setButtonPosition }),
    [messages, isOpen, unreadCount, buttonPosition, typingBot, openChat, closeChat, toggleChat, setButtonPosition]
  );

  return <AiChatContext.Provider value={value}>{children}</AiChatContext.Provider>;
}

export function useAiChat() {
  return useContext(AiChatContext);
}
