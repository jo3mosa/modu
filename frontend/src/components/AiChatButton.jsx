import { useEffect, useRef, useState } from 'react';
import { MessageCircle, X } from 'lucide-react';
import { useAiChat } from '../hooks/useAiChat';
import AiChatPanel from './AiChatPanel';
import './AiChatButton.css';

const BUTTON_SIZE = 56;
const EDGE_PADDING = 16;
// 마우스 다운에서 이 거리 이하로 움직였으면 클릭으로 간주 (드래그 vs 클릭 구분)
const CLICK_THRESHOLD_PX = 5;

/**
 * 전역 플로팅 AI 채팅 버튼.
 * - 우측 하단 기본, 드래그로 화면 어디든 이동 (마우스/터치)
 * - 새 메시지 있으면 우측 상단 빨간 뱃지
 * - 클릭 시 AiChatPanel 토글
 * - 드래그 종료 시 위치 localStorage 저장
 */
export default function AiChatButton() {
  const { isOpen, unreadCount, buttonPosition, setButtonPosition, toggleChat, closeChat } = useAiChat();
  const buttonRef = useRef(null);

  // 초기 위치 — 저장된 값 없으면 우측 하단
  const [pos, setPos] = useState(() => buttonPosition ?? getDefaultPos());
  const dragStateRef = useRef({ dragging: false, startX: 0, startY: 0, origX: 0, origY: 0, moved: false });

  function getDefaultPos() {
    const x = window.innerWidth - BUTTON_SIZE - EDGE_PADDING;
    const y = window.innerHeight - BUTTON_SIZE - EDGE_PADDING;
    return { x, y };
  }

  // 창 크기 변경 시 화면 밖으로 튀지 않게 보정
  useEffect(() => {
    function clampToViewport() {
      setPos((p) => clamp(p, BUTTON_SIZE));
    }
    window.addEventListener('resize', clampToViewport);
    return () => window.removeEventListener('resize', clampToViewport);
  }, []);

  // 외부에서 setButtonPosition으로 강제 변경된 경우 반영
  useEffect(() => {
    if (buttonPosition) setPos(clamp(buttonPosition, BUTTON_SIZE));
  }, [buttonPosition]);

  function clamp(p, size) {
    const maxX = window.innerWidth - size - EDGE_PADDING;
    const maxY = window.innerHeight - size - EDGE_PADDING;
    return {
      x: Math.min(Math.max(EDGE_PADDING, p.x), Math.max(EDGE_PADDING, maxX)),
      y: Math.min(Math.max(EDGE_PADDING, p.y), Math.max(EDGE_PADDING, maxY)),
    };
  }

  function handlePointerDown(e) {
    e.preventDefault();
    const point = pointerXY(e);
    dragStateRef.current = {
      dragging: true,
      startX: point.x,
      startY: point.y,
      origX: pos.x,
      origY: pos.y,
      moved: false,
    };
    window.addEventListener('mousemove', handlePointerMove);
    window.addEventListener('mouseup', handlePointerUp);
    window.addEventListener('touchmove', handlePointerMove, { passive: false });
    window.addEventListener('touchend', handlePointerUp);
  }

  function pointerXY(e) {
    if (e.touches && e.touches.length > 0) {
      return { x: e.touches[0].clientX, y: e.touches[0].clientY };
    }
    return { x: e.clientX, y: e.clientY };
  }

  function handlePointerMove(e) {
    const state = dragStateRef.current;
    if (!state.dragging) return;
    if (e.cancelable) e.preventDefault();
    const point = pointerXY(e);
    const dx = point.x - state.startX;
    const dy = point.y - state.startY;
    if (!state.moved && Math.hypot(dx, dy) > CLICK_THRESHOLD_PX) {
      state.moved = true;
    }
    setPos(clamp({ x: state.origX + dx, y: state.origY + dy }, BUTTON_SIZE));
  }

  function handlePointerUp() {
    const state = dragStateRef.current;
    window.removeEventListener('mousemove', handlePointerMove);
    window.removeEventListener('mouseup', handlePointerUp);
    window.removeEventListener('touchmove', handlePointerMove);
    window.removeEventListener('touchend', handlePointerUp);
    if (!state.dragging) return;

    // 드래그가 아니라 클릭이었으면 토글
    if (!state.moved) {
      toggleChat();
    } else {
      // 드래그 결과 위치 저장
      setButtonPosition(pos);
    }
    dragStateRef.current = { ...state, dragging: false };
  }

  return (
    <>
      <button
        ref={buttonRef}
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
