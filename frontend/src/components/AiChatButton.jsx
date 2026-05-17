import { useEffect, useRef, useState } from 'react';
import { MessageCircle, X } from 'lucide-react';
import { useAiChat } from '../hooks/useAiChat';
import AiChatPanel from './AiChatPanel';
import './AiChatButton.css';

const BUTTON_SIZE = 56;
const EDGE_PADDING = 16;
// 마우스 다운에서 이 거리 이하로 움직였으면 클릭으로 간주 (드래그 vs 클릭 구분)
const CLICK_THRESHOLD_PX = 5;

function pointerXY(e) {
  if (e.touches && e.touches.length > 0) return { x: e.touches[0].clientX, y: e.touches[0].clientY };
  if (e.changedTouches && e.changedTouches.length > 0) return { x: e.changedTouches[0].clientX, y: e.changedTouches[0].clientY };
  return { x: e.clientX, y: e.clientY };
}

function clamp(p, size) {
  const maxX = window.innerWidth - size - EDGE_PADDING;
  const maxY = window.innerHeight - size - EDGE_PADDING;
  return {
    x: Math.min(Math.max(EDGE_PADDING, p.x), Math.max(EDGE_PADDING, maxX)),
    y: Math.min(Math.max(EDGE_PADDING, p.y), Math.max(EDGE_PADDING, maxY)),
  };
}

/**
 * 전역 플로팅 AI 채팅 버튼.
 * 드래그 로직은 dragging state 기반 useEffect 패턴 — listener cleanup 보장 + closure 안전.
 */
export default function AiChatButton() {
  const { isOpen, unreadCount, buttonPosition, setButtonPosition, toggleChat, closeChat } = useAiChat();
  const [pos, setPos] = useState(() => buttonPosition ?? getDefaultPos());
  const [dragging, setDragging] = useState(false);
  // mousedown 시점 정보 — 매 드래그마다 갱신, render 트리거 불필요해 ref 사용
  const dragStartRef = useRef({ startX: 0, startY: 0, origX: 0, origY: 0, moved: false });

  function getDefaultPos() {
    return {
      x: window.innerWidth - BUTTON_SIZE - EDGE_PADDING,
      y: window.innerHeight - BUTTON_SIZE - EDGE_PADDING,
    };
  }

  // 외부에서 위치가 바뀌면 동기화
  useEffect(() => {
    if (buttonPosition) setPos(clamp(buttonPosition, BUTTON_SIZE));
  }, [buttonPosition]);

  // 창 크기 변경 시 화면 밖으로 튀지 않게 보정
  useEffect(() => {
    const onResize = () => setPos((p) => clamp(p, BUTTON_SIZE));
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  // 드래그 중일 때만 window listener 등록 — cleanup으로 누수 차단
  useEffect(() => {
    if (!dragging) return;

    const onMove = (e) => {
      if (e.cancelable) e.preventDefault();
      const { x, y } = pointerXY(e);
      const start = dragStartRef.current;
      const dx = x - start.startX;
      const dy = y - start.startY;
      if (!start.moved && Math.hypot(dx, dy) > CLICK_THRESHOLD_PX) {
        start.moved = true;
      }
      setPos(clamp({ x: start.origX + dx, y: start.origY + dy }, BUTTON_SIZE));
    };

    const onUp = () => {
      setDragging(false);
      if (dragStartRef.current.moved) {
        // setPos updater 형태로 최신 pos에 접근해서 영속 저장
        setPos((p) => {
          setButtonPosition(p);
          return p;
        });
      } else {
        // 거의 안 움직였으면 클릭으로 처리
        toggleChat();
      }
    };

    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
    window.addEventListener('touchmove', onMove, { passive: false });
    window.addEventListener('touchend', onUp);
    return () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
      window.removeEventListener('touchmove', onMove);
      window.removeEventListener('touchend', onUp);
    };
  }, [dragging, toggleChat, setButtonPosition]);

  function handlePointerDown(e) {
    // mousedown 자체의 기본 동작(focus 등)은 막지 않음 — 드래그 트래킹만 시작
    const { x, y } = pointerXY(e);
    dragStartRef.current = {
      startX: x,
      startY: y,
      origX: pos.x,
      origY: pos.y,
      moved: false,
    };
    setDragging(true);
  }

  return (
    <>
      <button
        type="button"
        className={`ai-chat-button ${isOpen ? 'is-open' : ''}`}
        style={{ left: pos.x, top: pos.y, width: BUTTON_SIZE, height: BUTTON_SIZE }}
        onMouseDown={handlePointerDown}
        onTouchStart={handlePointerDown}
        aria-label={isOpen ? 'AI 채팅 닫기' : 'AI 채팅 열기'}
      >
        {isOpen ? <X size={24} /> : <MessageCircle size={26} />}
        {!isOpen && unreadCount > 0 && (
          <span className="ai-chat-button-badge">{unreadCount > 9 ? '9+' : unreadCount}</span>
        )}
      </button>

      {isOpen && <AiChatPanel anchor={pos} onClose={closeChat} />}
    </>
  );
}
