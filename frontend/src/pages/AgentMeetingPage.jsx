import { useState, useEffect, useRef } from 'react';
import { Search } from 'lucide-react';
import { getPortfolio } from '../api/account';
import { getStocks } from '../api/market';
import { getAiDecisions } from '../api/aiAgent';
import { useAiChat, BOT_PROFILES, decisionToMessages } from '../hooks/useAiChat';
import './AgentMeetingPage.css';

export default function AgentMeetingPage() {
  const [channels, setChannels] = useState([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedStock, setSelectedStock] = useState(null);
  const [historyMessages, setHistoryMessages] = useState([]);
  const [isLoadingChannels, setIsLoadingChannels] = useState(true);

  const { messages: liveMessages, typingBot } = useAiChat();
  const feedRef = useRef(null);

  // 1. 관심/보유 종목(채널) 로드
  useEffect(() => {
    let mounted = true;
    const fetchChannels = async () => {
      try {
        const portfolio = await getPortfolio();
        let list = portfolio?.holdings ?? [];

        // 보유 종목이 없으면 기본 5개 종목 노출
        if (list.length === 0) {
          const defaultStocks = await getStocks({ size: 5 });
          list = defaultStocks?.stocks ?? [];
        }

        if (mounted) {
          setChannels(list);
          if (list.length > 0) {
            setSelectedStock(list[0]);
          }
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

  // 2. 선택된 채널이 바뀔 때 과거 데이터 페칭
  useEffect(() => {
    if (!selectedStock) return;
    let mounted = true;

    const fetchHistory = async () => {
      try {
        const res = await getAiDecisions({ stockCode: selectedStock.stockCode, size: 20 });
        const decisions = res?.content ?? [];

        if (mounted) {
          // 오래된 순으로 정렬 후 메시지 변환
          const msgs = [...decisions]
            .reverse()
            .flatMap(d => decisionToMessages(d));
          setHistoryMessages(msgs);
        }
      } catch (err) {
        console.error('Failed to load history decisions', err);
      }
    };

    fetchHistory();
    return () => { mounted = false; };
  }, [selectedStock]);

  // 3. 스크롤을 맨 아래로
  useEffect(() => {
    if (feedRef.current) {
      feedRef.current.scrollTop = feedRef.current.scrollHeight;
    }
  }, [historyMessages, liveMessages, typingBot, selectedStock]);

  const handleChannelSelect = (channel) => {
    setSelectedStock(channel);
  };

  // 현재 종목에 해당하는 실시간 메시지 필터링
  const currentLiveMessages = selectedStock 
    ? liveMessages.filter(m => m.stockCode === selectedStock.stockCode)
    : [];

  // 과거 + 실시간 메시지 병합 (중복 방지: ID 기준)
  const allMessagesMap = new Map();
  historyMessages.forEach(m => allMessagesMap.set(m.id, m));
  currentLiveMessages.forEach(m => allMessagesMap.set(m.id, m));
  const mergedMessages = Array.from(allMessagesMap.values())
    .sort((a, b) => new Date(a.timestamp) - new Date(b.timestamp));

  const filteredChannels = channels.filter(c => 
    c.stockName.includes(searchQuery) || c.stockCode.includes(searchQuery)
  );

  const getUnreadCountForChannel = (stockCode) => {
    // 실시간 메시지 중 해당 채널의 메시지 수 (예시용 간단 계산)
    return liveMessages.filter(m => m.stockCode === stockCode).length;
  };

  return (
    <div className="agent-meeting-container">
      {/* 1. 좌측 채널 리스트 */}
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
          ) : filteredChannels.map(channel => {
            const isActive = selectedStock?.stockCode === channel.stockCode;
            const unreadCount = getUnreadCountForChannel(channel.stockCode);
            // 모의 데이터 등으로 price가 없으면 '-' 처리
            const price = channel.currentPrice ? channel.currentPrice.toLocaleString() : '-';
            const rate = channel.pnlPct ?? channel.compareRate ?? 0;
            const rateClass = rate > 0 ? 'up' : rate < 0 ? 'down' : 'steady';

            return (
              <div 
                key={channel.stockCode} 
                className={`channel-item ${isActive ? 'active' : ''}`}
                onClick={() => handleChannelSelect(channel)}
              >
                <div className="channel-info">
                  <div className="channel-name">{channel.stockName}</div>
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

      {/* 2. 우측 채팅 피드 */}
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
              <div key={idx} className="agent-icon-small" style={{ backgroundColor: bot.color }}>
                {bot.icon}
              </div>
            ))}
          </div>
        </div>

        <div className="chat-feed" ref={feedRef}>
          {selectedStock && mergedMessages.length === 0 && !typingBot && (
            <div className="empty-chat">
              <p>아직 이 종목에 대한 AI 에이전트 토론이 없습니다.</p>
            </div>
          )}

          {mergedMessages.map((msg, i) => {
            const bot = BOT_PROFILES[msg.bot];
            if (!bot) return null;

            const timeStr = new Date(msg.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
            // 간단하게 하루가 바뀌는 경계를 처리할 수도 있으나, 여기선 생략
            
            return (
              <div key={msg.id} className="chat-message">
                <div className="agent-avatar" style={{ backgroundColor: bot.color }}>
                  {bot.icon}
                </div>
                <div className="message-content">
                  <div className="message-header">
                    <span className="message-agent-name">{bot.name}</span>
                    <span className="message-agent-role">{bot.role} Agent</span>
                    <span className="message-time">{timeStr}</span>
                  </div>
                  
                  <div className="message-text">
                    {msg.text}
                  </div>
                </div>
              </div>
            );
          })}

          {/* 현재 채널에서 타이핑 중인 봇 표시 */}
          {typingBot && (() => {
            const bot = BOT_PROFILES[typingBot];
            if (!bot) return null;
            // 타이핑은 useAiChat이 전역으로 관리하므로 종목 구분이 안될 수 있음 (모의 환경 한계)
            // 여기선 표시해줍니다.
            return (
              <div className="typing-indicator">
                <div className="agent-avatar" style={{ backgroundColor: bot.color }}>
                  {bot.icon}
                </div>
                <div className="message-content">
                  <div className="message-header">
                    <span className="message-agent-name">{bot.name}</span>
                  </div>
                  <div className="typing-dots">
                    <span></span><span></span><span></span>
                  </div>
                </div>
              </div>
            );
          })()}
        </div>
      </div>
    </div>
  );
}
