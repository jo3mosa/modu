import { useEffect, useMemo, useRef } from 'react';
import { X } from 'lucide-react';
import { useAiChat, BOT_PROFILES } from '../hooks/useAiChat';
import './AiChatPanel.css';

const PANEL_WIDTH = 360;
const PANEL_HEIGHT = 480;
const BUTTON_SIZE = 56;
const VIEWPORT_PADDING = 12;

/**
 * 4봇 채팅 위젯. 버튼 위치 기준 좌상단/좌하단/우상단/우하단 중 화면에 맞는 곳에 띄움.
 * 판단 단위로 묶어 시간순(오래된 위 → 최신 아래) 표시.
 */
export default function AiChatPanel({ anchor, onClose }) {
  const { messages, typingBot } = useAiChat();
  const scrollRef = useRef(null);

  // anchor(버튼 좌상단)에서 화면 밖으로 안 나가게 패널 위치 계산
  const panelStyle = useMemo(() => positionPanel(anchor), [anchor]);

  // 새 메시지/타이핑 변화 시 자동 스크롤 (맨 아래로)
  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages.length, typingBot]);

  // 판단(decisionId) 단위로 그룹 — 한 판단 = 한 회의록
  const groups = useMemo(() => groupByDecision(messages), [messages]);

  return (
    <div
      className="ai-chat-panel"
      style={{ ...panelStyle, width: PANEL_WIDTH, height: PANEL_HEIGHT }}
      role="dialog"
      aria-label="AI 에이전트 회의록"
    >
      <header className="ai-chat-header">
        <span className="ai-chat-title">AI 회의록</span>
        <button type="button" className="ai-chat-close" onClick={onClose} aria-label="닫기">
          <X size={18} />
        </button>
      </header>

      <div className="ai-chat-body" ref={scrollRef}>
        {groups.length === 0 ? (
          <div className="ai-chat-empty">
            아직 AI 판단이 없습니다.
            <br />
            자동매매가 시작되면 여기에 토론이 표시돼요.
          </div>
        ) : (
          groups.map((group) => (
            <div key={group.decisionId} className="ai-chat-group">
              <div className="ai-chat-group-meta">
                <span className="ai-chat-group-stock">{group.stockCode}</span>
                <span className="ai-chat-group-time">{formatTime(group.timestamp)}</span>
              </div>
              {group.messages.map((msg) => {
                const profile = BOT_PROFILES[msg.bot] ?? BOT_PROFILES.decide;
                return (
                  <div key={msg.id} className="ai-chat-message">
                    <div className="ai-chat-avatar" style={{ background: `${profile.color}22`, color: profile.color }}>
                      <span aria-hidden="true">{profile.icon}</span>
                    </div>
                    <div className="ai-chat-bubble-wrap">
                      <div className="ai-chat-bot-name" style={{ color: profile.color }}>
                        {profile.name}
                        {profile.role && <span className="ai-chat-bot-role"> · {profile.role}</span>}
                      </div>
                      <div className="ai-chat-bubble">{msg.text}</div>
                    </div>
                  </div>
                );
              })}
            </div>
          ))
        )}

        {/* 타이핑 인디케이터 — 다음 봇이 입력 중일 때 표시 */}
        {typingBot && BOT_PROFILES[typingBot] && (
          <div className="ai-chat-message ai-chat-typing-row">
            <div
              className="ai-chat-avatar"
              style={{ background: `${BOT_PROFILES[typingBot].color}22`, color: BOT_PROFILES[typingBot].color }}
            >
              <span aria-hidden="true">{BOT_PROFILES[typingBot].icon}</span>
            </div>
            <div className="ai-chat-bubble-wrap">
              <div className="ai-chat-bot-name" style={{ color: BOT_PROFILES[typingBot].color }}>
                {BOT_PROFILES[typingBot].name}
                <span className="ai-chat-bot-role"> · 입력 중</span>
              </div>
              <div className="ai-chat-bubble ai-chat-typing">
                <span className="ai-chat-typing-dot" />
                <span className="ai-chat-typing-dot" />
                <span className="ai-chat-typing-dot" />
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

function positionPanel(anchor) {
  const margin = 12; // 버튼과 패널 사이 간격
  const vw = window.innerWidth;
  const vh = window.innerHeight;
  if (!anchor) {
    return { left: vw - PANEL_WIDTH - VIEWPORT_PADDING, top: vh - PANEL_HEIGHT - BUTTON_SIZE - margin - VIEWPORT_PADDING };
  }

  // 버튼 좌측에 패널을 둘지 우측에 둘지 — 더 공간 넓은 쪽
  const spaceRight = vw - (anchor.x + BUTTON_SIZE);
  const placeLeft = spaceRight < PANEL_WIDTH + margin + VIEWPORT_PADDING;
  let left = placeLeft ? anchor.x - PANEL_WIDTH - margin : anchor.x + BUTTON_SIZE + margin;

  // 위/아래 중 화면에 더 잘 들어가는 쪽 — 위쪽으로 띄우는 게 디폴트
  const top = Math.max(
    VIEWPORT_PADDING,
    Math.min(anchor.y - (PANEL_HEIGHT - BUTTON_SIZE), vh - PANEL_HEIGHT - VIEWPORT_PADDING)
  );

  // 좌우 모두 화면 안에 들어오게 clamp
  left = Math.max(VIEWPORT_PADDING, Math.min(left, vw - PANEL_WIDTH - VIEWPORT_PADDING));
  return { left, top };
}

function groupByDecision(messages) {
  const map = new Map();
  for (const msg of messages) {
    if (!map.has(msg.decisionId)) {
      map.set(msg.decisionId, {
        decisionId: msg.decisionId,
        stockCode: msg.stockCode,
        timestamp: msg.timestamp,
        messages: [],
      });
    }
    map.get(msg.decisionId).messages.push(msg);
  }
  return Array.from(map.values()).sort(
    (a, b) => new Date(a.timestamp) - new Date(b.timestamp)
  );
}

function formatTime(iso) {
  try {
    const d = new Date(iso);
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    const hh = String(d.getHours()).padStart(2, '0');
    const mm = String(d.getMinutes()).padStart(2, '0');
    return `${m}/${day} ${hh}:${mm}`;
  } catch {
    return '';
  }
}
