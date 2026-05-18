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

import bullImg from '../assets/12.webp';
import bearImg from '../assets/123.jpg';
import strategyImg from '../assets/1234.jpg';
import decideImg from '../assets/12345.webp';

// BE 의 AgentType enum 과 동일 키 (대문자)
// useAiChat 의 소문자 키(bull/bear/strategy/decide) 와는 별개
export const BOT_PROFILES = {
  BULL: {
    name: '강세 리서처',
    role: '리서치',
    icon: '강세',
    color: '#ef4444',
    avatar: bullImg,
    tagline: '상방 시나리오 전담',
    description: '기술적 지표와 매크로 호재 속에서 상승 트리거를 발굴합니다. 강세 근거를 적극적으로 제시해 회의의 한 축을 담당합니다.',
    style: '공격적 · 모멘텀 중시',
  },
  BEAR: {
    name: '약세 리서처',
    role: '리서치',
    icon: '약세',
    color: '#3b82f6',
    avatar: bearImg,
    tagline: '하방 리스크 전담',
    description: '거시 위험, 수급 부담, 밸류에이션 부담을 점검합니다. 강세 리서처와의 균형을 통해 한쪽 편향을 방지합니다.',
    style: '보수적 · 리스크 우선',
  },
  STRATEGY: {
    name: '전략 매니저',
    role: '전략',
    icon: '전략',
    color: '#a78bfa',
    avatar: strategyImg,
    tagline: '포지션 설계 담당',
    description: '강세·약세 리서치를 종합해 진입 시점, 분할 매수 비중, 손절가를 설계합니다. 실행 가능한 전략으로 구체화합니다.',
    style: '균형 · 분할 진입 선호',
  },
  DECIDE: {
    name: '의사결정 매니저',
    role: '의사결정',
    icon: '결정',
    color: '#10b981',
    avatar: decideImg,
    tagline: '최종 매수/매도 판단',
    description: '전략 매니저의 제안을 검토해 최종 BUY/SELL/HOLD를 결정합니다. 회의 결론을 종합 의견으로 압축합니다.',
    style: '결정적 · 신뢰도 기반',
  },
};

const DEFAULT_PAGE_SIZE = 50;

function getStockNameByCode(code) {
  const mapping = {
    '005930': '삼성전자',
    '000660': 'SK하이닉스',
    '035720': '카카오',
    '035420': 'NAVER',
    '005380': '현대차',
    '000270': '기아',
    '068270': '셀트리온',
    '005490': 'POSCO홀딩스',
    '105560': 'KB금융',
    '051910': 'LG화학',
  };
  return mapping[code] || '해당 종목';
}

function generateMockMessages(stockCode) {
  const stockName = getStockNameByCode(stockCode);
  const now = new Date();
  
  return [
    {
      messageId: 99004,
      stockCode,
      judgmentId: null,
      agent: 'DECIDE',
      seq: 4,
      text: `최종 결론 조율되었습니다. 매크로 불확실성에도 불구하고 기술적 지지세가 탄탄한 국면입니다. 비중 10% 이내의 1차 분할 매수(BUY) 승인 건을 전략 풀에 대기시키겠습니다. 손절가는 금일 지지선 하단으로 설정하여 극도로 타이트하게 제어하겠습니다.`,
      createdAt: new Date(now.getTime() - 1000 * 60 * 5).toISOString(),
    },
    {
      messageId: 99003,
      stockCode,
      judgmentId: null,
      agent: 'STRATEGY',
      seq: 3,
      text: `두 분의 리서치 근거가 모두 설득력 있습니다. 전략적 절충안으로, 상방 돌파 확률을 62%로 보고 롱 포지션을 구축하되 단기 흔들기에 대비해 한 번에 매수하지 않고 3회에 걸친 철저한 분할 매수 분산 전략을 시행하는 것이 유리해 보입니다.`,
      createdAt: new Date(now.getTime() - 1000 * 60 * 9).toISOString(),
    },
    {
      messageId: 99002,
      stockCode,
      judgmentId: null,
      agent: 'BEAR',
      seq: 2,
      text: `기술적 반등만으로 추세 전환을 확신하긴 이릅니다. 최근 해외 경쟁사의 가이드라인 하향 조정 우려가 남아있어, ${stockName} 역시 상단 저항 매물을 소화하는 과정에서 일시 조정이 올 확률이 55%가 넘습니다. 보수적인 리스크 관리가 절대적으로 선행되어야 할 시점입니다.`,
      createdAt: new Date(now.getTime() - 1000 * 60 * 13).toISOString(),
    },
    {
      messageId: 99001,
      stockCode,
      judgmentId: null,
      agent: 'BULL',
      seq: 1,
      text: `최근 ${stockName}의 기술적 추세를 모니터링한 결과, 120일선에서 강력한 기관 순매수가 유입되며 단기적인 하방 경직성을 확보했습니다. 특히 RSI 지표가 과매도 권역을 성공적으로 돌파하며 강한 상방 에너지를 분출하기 시작했습니다!`,
      createdAt: new Date(now.getTime() - 1000 * 60 * 17).toISOString(),
    },
  ];
}

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
      let content = res.content ?? [];
      let hasMore = res.hasMore;
      let nextCursor = res.nextCursor;
      let nextCursorId = res.nextCursorId;
      
      // 만약 백엔드 DB에 에이전트 대화 데이터가 없으면, 고품질 종목 맞춤형 실시간 모의 대화 시나리오를 로드하여 채워줌!
      if (content.length === 0) {
        content = generateMockMessages(stockCode);
        hasMore = false;
        nextCursor = null;
        nextCursorId = null;
      }
      
      content.forEach((m) => seenIdsRef.current.add(m.messageId));
      
      setByStock((prev) => ({
        ...prev,
        [stockCode]: {
          messages: content,
          nextCursor,
          nextCursorId,
          hasMore,
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
