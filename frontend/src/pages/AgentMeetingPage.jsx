import { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import { Search } from 'lucide-react';
import { getPortfolio } from '../api/account';
import { getStocks } from '../api/market';
import { useAgentChat, BOT_PROFILES } from '../hooks/useAgentChat';
import './AgentMeetingPage.css';

/**
 * 말풍선 글자 타이핑 애니메이션 및 동적 피드 스크롤 핸들링 컴포넌트
 */
function TypingMessageBubble({ text, isAnimated, onComplete, feedRef }) {
  const [displayedText, setDisplayedText] = useState(isAnimated ? '' : text);
  const textRef = useRef(text);
  textRef.current = text;

  useEffect(() => {
    if (!isAnimated) {
      setDisplayedText(text);
      return;
    }

    setDisplayedText('');
    let index = 0;
    const interval = setInterval(() => {
      setDisplayedText((prev) => {
        const next = prev + textRef.current.charAt(index);
        if (feedRef && feedRef.current) {
          // scroll-behavior: smooth와 충돌 없이 완벽하게 최하단으로 부드럽게 흐르도록 애니메이션 프레임에 맞춤
          feedRef.current.scrollTop = feedRef.current.scrollHeight;
        }
        return next;
      });
      index++;
      if (index >= textRef.current.length) {
        clearInterval(interval);
        if (onComplete) onComplete();
      }
    }, 38); // 기계적인 느낌을 탈피하고 가장 부드러우며 가독성이 극대화되는 38ms로 튜닝

    return () => clearInterval(interval);
  }, [isAnimated, feedRef, onComplete]);

  return (
    <div className="message-bubble">
      {displayedText}
      {isAnimated && displayedText.length < text.length && (
        <span className="typing-cursor">|</span>
      )}
    </div>
  );
}

/**
 * AI 에이전트 회의 페이지
 *
 * [데이터 흐름]
 *  - 좌측 채널 리스트: 보유 종목(없으면 기본 5개)
 *  - 우측 채팅 피드: useAgentChat 의 per-stock 버퍼 사용
 *    · 채널 진입 시 loadHistory 로 최신 50개 fetch
 *    · 위로 스크롤 시 IntersectionObserver 가 loadMore 호출
 *    · BE SSE 'agent-message' 수신 시 자동으로 맨 위(최신)에 prepend
 */
export default function AgentMeetingPage() {
  const [channels, setChannels] = useState([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedStock, setSelectedStock] = useState(null);
  const [isLoadingChannels, setIsLoadingChannels] = useState(true);

  const { byStock, unreadByStock, loadHistory, loadMore, markRead } = useAgentChat();

  const feedRef = useRef(null);
  const topSentinelRef = useRef(null);
  const prevScrollHeightRef = useRef(0);

  // ── 1. 채널 리스트 로드 ────────────────────────────────────────────────
  useEffect(() => {
    let mounted = true;
    const fetchChannels = async () => {
      try {
        const portfolio = await getPortfolio();
        let list = portfolio?.holdings ?? [];
        if (list.length === 0) {
          const defaultStocks = await getStocks({ size: 5 });
          list = defaultStocks?.stocks ?? [];
        }
        if (mounted) {
          setChannels(list);
          if (list.length > 0) setSelectedStock(list[0]);
          setIsLoadingChannels(false);
        }
      } catch (err) {
        console.error('Failed to load channels:', err);
        if (mounted) setIsLoadingChannels(false);
      }
    };
    fetchChannels();
    return () => { mounted = false; };
  }, []);

  // ── 2. 채널 선택 시 히스토리 로드 + 읽음 처리 ──────────────────────────
  useEffect(() => {
    if (!selectedStock) return;
    loadHistory(selectedStock.stockCode);
    markRead(selectedStock.stockCode);
  }, [selectedStock, loadHistory, markRead]);

  const channel = selectedStock ? byStock[selectedStock.stockCode] : null;
  const rawMessages = channel?.messages ?? [];

  // BE 응답은 DESC — 화면은 시간순(오래된 → 최신)으로 보이는 게 자연스러우므로 reverse
  const displayMessages = useMemo(() => [...rawMessages].reverse(), [rawMessages]);

  // ── 2.5 말풍선 순차 타이핑 애니메이션 로직 ──────────────────────────────────────
  const [revealedIds, setRevealedIds] = useState(new Set());
  const [activeAnimationId, setActiveAnimationId] = useState(null);
  const oldestMessageIdRef = useRef(null);

  // 채널(종목) 변경 시 타이핑 애니메이션 상태 초기화
  useEffect(() => {
    setRevealedIds(new Set());
    setActiveAnimationId(null);
    oldestMessageIdRef.current = null;
  }, [selectedStock?.stockCode]);

  // 메시지 배열 변경에 맞춰 순차 노출 큐 실행
  useEffect(() => {
    if (displayMessages.length === 0) return;

    // 1) 초기 채널 진입: 첫 번째 메시지부터 cascade 시작
    if (revealedIds.size === 0) {
      const firstMsg = displayMessages[0];
      oldestMessageIdRef.current = firstMsg.messageId;
      setRevealedIds(new Set([firstMsg.messageId]));
      setActiveAnimationId(firstMsg.messageId);
      return;
    }

    // 2) 스크롤 히스토리(과거 데이터) 또는 실시간 SSE 메시지 구분 처리
    let changed = false;
    const nextRevealed = new Set(revealedIds);

    displayMessages.forEach(msg => {
      if (!nextRevealed.has(msg.messageId)) {
        // 기존 가장 오래된 메시지보다 타임스탬프가 이전이면 '스크롤로 추가된 히스토리'로 인지하여 애니메이션 생략하고 즉시 노출
        const oldestMsg = displayMessages.find(m => m.messageId === oldestMessageIdRef.current);
        const isOlder = oldestMsg && new Date(msg.createdAt) < new Date(oldestMsg.createdAt);

        if (isOlder) {
          nextRevealed.add(msg.messageId);
          changed = true;
        } else {
          // 실시간으로 흘러들어온 최신 SSE 메시지인 경우에만 큐에 넣어 부드러운 타이핑 실행
          const isLatest = msg.messageId === displayMessages[displayMessages.length - 1].messageId;
          if (isLatest) {
            nextRevealed.add(msg.messageId);
            setActiveAnimationId(msg.messageId);
            changed = true;
          }
        }
      }
    });

    if (changed) {
      setRevealedIds(nextRevealed);
    }
  }, [displayMessages]);

  // 현재 활성화된 타이핑이 끝났을 때 다음 메시지를 이어서 타이핑하도록 트리거
  const handleTypeComplete = useCallback((msgId) => {
    setActiveAnimationId(null);

    const currentIndex = displayMessages.findIndex(m => m.messageId === msgId);
    if (currentIndex !== -1 && currentIndex + 1 < displayMessages.length) {
      const nextMsg = displayMessages[currentIndex + 1];
      setRevealedIds(prev => {
        const nextSet = new Set(prev);
        nextSet.add(nextMsg.messageId);
        return nextSet;
      });
      setActiveAnimationId(nextMsg.messageId);
    }
  }, [displayMessages]);

  // ── 3. 위로 스크롤 무한 로딩 (IntersectionObserver) ────────────────────
  useEffect(() => {
    const sentinel = topSentinelRef.current;
    if (!sentinel || !selectedStock || !channel?.hasMore) return;

    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting && feedRef.current) {
          // 추가 페이지 로드 전 스크롤 높이 기록 — 로드 후 보정해서 점프 방지
          prevScrollHeightRef.current = feedRef.current.scrollHeight;
          loadMore(selectedStock.stockCode);
        }
      },
      { root: feedRef.current, threshold: 0.1 },
    );
    observer.observe(sentinel);
    return () => observer.disconnect();
  }, [selectedStock, channel?.hasMore, channel?.nextCursorId, loadMore]);

  // ── 4. 스크롤 위치 보정 ────────────────────────────────────────────────
  // 새 메시지 도착 시(맨 아래) / 채널 전환 시: 맨 아래로
  // 위로 스크롤 페이지 추가 시: 위에 추가된 만큼 스크롤 보정 (점프 방지)
  const messagesLength = displayMessages.length;
  const prevLengthRef = useRef(0);
  const prevStockRef = useRef(null);

  useEffect(() => {
    const feed = feedRef.current;
    if (!feed) return;

    const stockChanged = prevStockRef.current !== selectedStock?.stockCode;
    const messageAdded = messagesLength > prevLengthRef.current;
    const messagesPrepended = prevScrollHeightRef.current > 0
      && feed.scrollHeight > prevScrollHeightRef.current
      && !stockChanged;

    if (messagesPrepended) {
      // 위에 메시지가 추가된 경우 — 사용자가 보던 위치 유지
      const diff = feed.scrollHeight - prevScrollHeightRef.current;
      feed.scrollTop = feed.scrollTop + diff;
      prevScrollHeightRef.current = 0;
    } else if (stockChanged || messageAdded) {
      // 채널 전환 또는 새 SSE 메시지 → 맨 아래
      feed.scrollTop = feed.scrollHeight;
    }

    prevLengthRef.current = messagesLength;
    prevStockRef.current = selectedStock?.stockCode;
  }, [messagesLength, selectedStock?.stockCode]);

  // ── 5. 채널 검색 / 선택 핸들러 ─────────────────────────────────────────
  const filteredChannels = channels.filter(c =>
    c.stockName.includes(searchQuery) || c.stockCode.includes(searchQuery),
  );

  const handleChannelSelect = (channelInfo) => {
    setSelectedStock(channelInfo);
  };

  // ── 6. 렌더 ───────────────────────────────────────────────────────────
  return (
    <div className="agent-meeting-container">
      {/* 좌측 채널 사이드바 */}
      <div className="channel-sidebar">
        <div className="channel-sidebar-header">
          <h2>종목 채널</h2>
          <div className="channel-search">
            <Search size={16} color="#666" />
            <input
              type="text"
              placeholder="검색"
              value={searchQuery}
              onChange={e => setSearchQuery(e.target.value)}
            />
          </div>
        </div>

        <div className="channel-list">
          {isLoadingChannels ? (
            <div style={{ padding: '1rem', color: '#666' }}>로딩 중...</div>
          ) : filteredChannels.map(channelInfo => {
            const isActive = selectedStock?.stockCode === channelInfo.stockCode;
            const unreadCount = unreadByStock[channelInfo.stockCode] ?? 0;
            const price = channelInfo.currentPrice ? channelInfo.currentPrice.toLocaleString() : '-';
            const rate = channelInfo.pnlPct ?? channelInfo.compareRate ?? 0;
            const rateClass = rate > 0 ? 'up' : rate < 0 ? 'down' : 'steady';

            return (
              <div
                key={channelInfo.stockCode}
                className={`channel-item ${isActive ? 'active' : ''}`}
                onClick={() => handleChannelSelect(channelInfo)}
              >
                <div className="channel-info">
                  <div className="channel-name">{channelInfo.stockName}</div>
                  <div className="channel-price">
                    <span>{price}원</span>
                    <span className={rateClass}>
                      {rate > 0 ? '+' : ''}{rate.toFixed(2)}%
                    </span>
                  </div>
                </div>
                {unreadCount > 0 && !isActive && (
                  <div className="channel-badge">{unreadCount > 9 ? '9+' : unreadCount}</div>
                )}
                {isActive && <div className="channel-badge" style={{ width: '8px', height: '8px' }}></div>}
              </div>
            );
          })}
        </div>
      </div>

      {/* 우측 채팅 피드 */}
      <div className="chat-panel">
        <div className="chat-header">
          <div className="chat-header-title">
            <h2>{selectedStock?.stockName ?? '선택된 종목 없음'}</h2>
            <span style={{ fontSize: '0.9rem', color: '#888' }}>
              {selectedStock?.stockCode}
            </span>
          </div>
          <div className="agent-roster">
            {Object.values(BOT_PROFILES).map((bot, idx) => (
              <div key={idx} className="agent-avatar-mini" title={bot.name}>
                <img src={bot.avatar} className="agent-avatar-mini-img" alt={bot.name} />
              </div>
            ))}
          </div>
        </div>

        <div className="chat-feed" ref={feedRef}>
          {/* 위로 스크롤 sentinel — 보이면 loadMore 트리거 */}
          {channel?.hasMore && (
            <div ref={topSentinelRef} style={{ padding: '0.5rem', textAlign: 'center', color: '#888' }}>
              이전 메시지 불러오는 중…
            </div>
          )}

          {selectedStock && channel?.loaded === true && displayMessages.length === 0 && (
            <div className="empty-chat">
              <p>아직 이 종목에 대한 AI 에이전트 토론이 없습니다.</p>
            </div>
          )}

          {displayMessages.filter(msg => revealedIds.has(msg.messageId)).map(msg => {
            const bot = BOT_PROFILES[msg.agent];
            if (!bot) return null;

            const timeStr = new Date(msg.createdAt).toLocaleTimeString([], {
              hour: '2-digit', minute: '2-digit',
            });

            const isAnimated = activeAnimationId === msg.messageId;

            return (
              <div key={msg.messageId} className={`chat-message agent-msg-${msg.agent.toLowerCase()}`}>
                <div className="agent-avatar-wrap">
                  <img src={bot.avatar} className="agent-avatar-img" alt={bot.name} />
                </div>
                <div className="message-content">
                  <div className="message-header">
                    <span className="message-agent-name">{bot.name}</span>
                    <span className="message-agent-role">{bot.role} Agent</span>
                  </div>
                  <div className="message-bubble-wrapper">
                    <TypingMessageBubble
                      text={msg.text}
                      isAnimated={isAnimated}
                      onComplete={() => handleTypeComplete(msg.messageId)}
                      feedRef={feedRef}
                    />
                    <span className="message-time">{timeStr}</span>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
