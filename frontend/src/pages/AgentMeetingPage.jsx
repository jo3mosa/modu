import { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import { Search, ArrowDown } from 'lucide-react';
import { getPortfolio } from '../api/account';
import { getStocks } from '../api/market';
import { useAgentChat, BOT_PROFILES } from '../hooks/useAgentChat';
import './AgentMeetingPage.css';

/**
 * "오늘 / 어제 / 2026년 5월 18일 (월)" 카톡 스타일 날짜 포매팅
 */
function formatDateDivider(date) {
  const today = new Date();
  const yesterday = new Date(); yesterday.setDate(yesterday.getDate() - 1);
  const sameDay = (a, b) => a.toDateString() === b.toDateString();

  if (sameDay(date, today)) return '오늘';
  if (sameDay(date, yesterday)) return '어제';

  const weekday = ['일', '월', '화', '수', '목', '금', '토'][date.getDay()];
  return `${date.getFullYear()}년 ${date.getMonth() + 1}월 ${date.getDate()}일 (${weekday})`;
}

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
    }, 18); // 빠르게 흐르되 타이핑 흔적이 한 호흡 보이는 속도

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

  // 우측 상단 roster 아바타 클릭 시 띄울 에이전트 프로필 모달 키 (BULL/BEAR/STRATEGY/DECIDE)
  const [profileAgentKey, setProfileAgentKey] = useState(null);

  // 사용자가 위쪽에서 과거 메시지를 읽는 중일 때 새로 들어온 메시지 카운트 — 플로팅 버튼에 표시
  const [newMessageCount, setNewMessageCount] = useState(0);

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

  // 메시지 배열 변경 처리 — 과거 메시지는 즉시 노출, 실시간 SSE 신규 메시지만 타이핑 애니메이션
  useEffect(() => {
    if (displayMessages.length === 0) return;

    // 1) 초기 채널 진입: 전체 과거 메시지를 한 번에 즉시 노출 (애니메이션 없음)
    if (revealedIds.size === 0) {
      oldestMessageIdRef.current = displayMessages[0].messageId;
      setRevealedIds(new Set(displayMessages.map((m) => m.messageId)));
      setActiveAnimationId(null);
      return;
    }

    // 2) 추가 메시지 — 과거 페이지(loadMore)는 즉시 노출, 새 SSE 메시지는 타이핑 애니메이션
    let changed = false;
    let latestNewMsgId = null;
    const nextRevealed = new Set(revealedIds);
    const oldestMsg = displayMessages.find((m) => m.messageId === oldestMessageIdRef.current);

    displayMessages.forEach((msg) => {
      if (nextRevealed.has(msg.messageId)) return;
      const isOlder = oldestMsg && new Date(msg.createdAt) < new Date(oldestMsg.createdAt);
      nextRevealed.add(msg.messageId);
      changed = true;
      if (!isOlder) latestNewMsgId = msg.messageId; // 신규(미래 방향) 메시지 중 가장 마지막 것만 타이핑 대상
    });

    if (changed) {
      setRevealedIds(nextRevealed);
      if (latestNewMsgId != null) setActiveAnimationId(latestNewMsgId);
    }
  }, [displayMessages]);

  // 타이핑이 끝나면 활성 애니메이션 해제 — cascade로 다음 메시지를 트리거하지는 않음
  const handleTypeComplete = useCallback(() => {
    setActiveAnimationId(null);
  }, []);

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
    } else if (stockChanged) {
      // 채널 전환 → 무조건 맨 아래 + 누적 카운트 리셋
      feed.scrollTop = feed.scrollHeight;
      setNewMessageCount(0);
    } else if (messageAdded) {
      // 새 SSE 메시지 — 사용자가 맨 아래 근처에 있으면 따라가고, 위에서 과거를 읽는 중이면 카운트만 증가
      const distanceFromBottom = feed.scrollHeight - feed.scrollTop - feed.clientHeight;
      if (distanceFromBottom < 80) {
        feed.scrollTop = feed.scrollHeight;
      } else {
        const delta = messagesLength - prevLengthRef.current;
        setNewMessageCount((prev) => prev + delta);
      }
    }

    prevLengthRef.current = messagesLength;
    prevStockRef.current = selectedStock?.stockCode;
  }, [messagesLength, selectedStock?.stockCode]);

  // 스크롤이 다시 맨 아래에 도달하면 "새 메시지 N개" 카운트를 자동 리셋
  const handleFeedScroll = useCallback(() => {
    const feed = feedRef.current;
    if (!feed) return;
    const distanceFromBottom = feed.scrollHeight - feed.scrollTop - feed.clientHeight;
    if (distanceFromBottom < 80) {
      setNewMessageCount((prev) => (prev === 0 ? prev : 0));
    }
  }, []);

  // 플로팅 버튼 클릭 → 맨 아래로 점프
  const handleJumpToBottom = useCallback(() => {
    const feed = feedRef.current;
    if (!feed) return;
    feed.scrollTop = feed.scrollHeight;
    setNewMessageCount(0);
  }, []);

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
      {/* 다른 페이지와 동일한 글로벌 페이지 헤더 */}
      <div className="page-header-container">
        <div className="page-title-group">
          <h1>에이전트 회의실</h1>
          <p>AI 에이전트들이 종목별로 분석·토론하는 과정을 실시간으로 확인하세요.</p>
        </div>
      </div>

      {/* 좌우 분할 본문 — 헤더 아래 남은 공간을 모두 차지 */}
      <div className="agent-meeting-body">
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
            {Object.entries(BOT_PROFILES).map(([key, bot]) => (
              <div
                key={key}
                className="agent-avatar-mini"
                title={`${bot.name} — 클릭하면 소개를 볼 수 있어요`}
                onClick={() => setProfileAgentKey(key)}
              >
                <img src={bot.avatar} className="agent-avatar-mini-img" alt={bot.name} />
              </div>
            ))}
          </div>
        </div>

        <div className="chat-feed" ref={feedRef} onScroll={handleFeedScroll}>
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

          {(() => {
            // 날짜 디바이더 + 연속 메시지 그룹핑을 위한 렌더 아이템 빌드
            const items = [];
            let prevDateKey = null;
            let prevAgent = null;
            let prevMinuteKey = null;

            for (const msg of displayMessages) {
              if (!revealedIds.has(msg.messageId)) continue;
              const date = new Date(msg.createdAt);
              const dateKey = date.toDateString();

              if (dateKey !== prevDateKey) {
                items.push({ kind: 'divider', key: `d-${dateKey}`, date });
                prevDateKey = dateKey;
                // 새 날짜에서는 그룹 초기화
                prevAgent = null;
                prevMinuteKey = null;
              }

              const minuteKey = `${date.getHours()}:${date.getMinutes()}`;
              // 같은 에이전트가 같은 분 내에 이어 말한 경우만 카톡식 연속 메시지로 묶음
              const isContinuation = msg.agent === prevAgent && minuteKey === prevMinuteKey;

              items.push({ kind: 'message', key: `m-${msg.messageId}`, msg, date, isContinuation });
              prevAgent = msg.agent;
              prevMinuteKey = minuteKey;
            }

            return items.map((item) => {
              if (item.kind === 'divider') {
                return (
                  <div key={item.key} className="date-divider">
                    <span>{formatDateDivider(item.date)}</span>
                  </div>
                );
              }

              const { msg, date, isContinuation } = item;
              const bot = BOT_PROFILES[msg.agent];
              if (!bot) return null;

              const timeStr = date.toLocaleTimeString([], {
                hour: '2-digit', minute: '2-digit',
              });
              const isAnimated = activeAnimationId === msg.messageId;
              const groupClass = isContinuation ? 'group-continuation' : 'group-start';

              return (
                <div key={item.key} className={`chat-message agent-msg-${msg.agent.toLowerCase()} ${groupClass}`}>
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
                        onComplete={handleTypeComplete}
                        feedRef={feedRef}
                      />
                      {/* 같은 그룹 내 중간 메시지는 시간 중복 노출 방지 */}
                      {!isContinuation && <span className="message-time">{timeStr}</span>}
                    </div>
                  </div>
                </div>
              );
            });
          })()}
        </div>

        {/* 위에서 과거 메시지 읽는 중 새 SSE 메시지가 도착했을 때만 노출되는 플로팅 점프 버튼 */}
        {newMessageCount > 0 && (
          <button type="button" className="new-messages-pill" onClick={handleJumpToBottom}>
            <ArrowDown size={14} />
            새 메시지 {newMessageCount}개
          </button>
        )}
      </div>
      </div> {/* .agent-meeting-body */}

      {/* 에이전트 프로필 모달 — roster 아바타 클릭 시 캐릭터 소개 노출 */}
      {profileAgentKey && BOT_PROFILES[profileAgentKey] && (
        <div className="profile-modal-backdrop" onClick={() => setProfileAgentKey(null)}>
          <div className="profile-modal" onClick={(e) => e.stopPropagation()}>
            <div className="profile-modal-header">
              <div className="profile-modal-avatar">
                <img src={BOT_PROFILES[profileAgentKey].avatar} alt={BOT_PROFILES[profileAgentKey].name} />
              </div>
              <div className="profile-modal-name">
                <h3>{BOT_PROFILES[profileAgentKey].name}</h3>
                <span className="profile-modal-tagline">{BOT_PROFILES[profileAgentKey].tagline}</span>
              </div>
            </div>
            <p className="profile-modal-description">
              {BOT_PROFILES[profileAgentKey].description}
            </p>
            <div className="profile-modal-meta">
              <span className="profile-modal-tag">역할 · {BOT_PROFILES[profileAgentKey].role}</span>
              <span className="profile-modal-tag">스타일 · {BOT_PROFILES[profileAgentKey].style}</span>
            </div>
            <button type="button" className="profile-modal-close" onClick={() => setProfileAgentKey(null)}>
              닫기
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
