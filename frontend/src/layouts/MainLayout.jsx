import { useState, useEffect, useRef } from 'react';
import { Outlet, Link, useLocation, useNavigate } from 'react-router-dom';
import { Search, Bell } from 'lucide-react';
import { toast } from 'sonner';
import { getStocks } from '../api/market';
import { useOrderSSE } from '../hooks/useOrderSSE';
import { useNotifications, NOTIFICATION_TYPE_META } from '../hooks/useNotifications';
import { usePendingDecisions } from '../hooks/usePendingDecisions';
import PendingDecisionsModal from '../components/PendingDecisionsModal';
import './MainLayout.css';

export default function MainLayout() {
  const location = useLocation();
  const navigate = useNavigate();

  const [query, setQuery] = useState('');
  const [results, setResults] = useState([]);
  const [showDropdown, setShowDropdown] = useState(false);
  const searchRef = useRef(null);
  const debounceRef = useRef(null);

  // 알림 팝업 / 승인 모달 토글
  const [isAlarmOpen, setIsAlarmOpen] = useState(false);
  const [isPendingModalOpen, setIsPendingModalOpen] = useState(false);
  const alarmRef = useRef(null);

  // 전역 알림 + 승인 대기 — Provider에서 제공
  const { notifications, unreadCount, addNotification, markAllAsRead } = useNotifications();
  const { pending } = usePendingDecisions();
  const pendingCount = pending.length;
  // Bell 뱃지 — 안 읽은 알림 + 승인 대기 합산 (사용자 인지 영역 통합)
  const bellBadgeCount = unreadCount + pendingCount;

  const menuItems = [
    { path: '/home', label: '대시보드' },
    { path: '/trading', label: '트레이딩 룸' },
    { path: '/agent-meeting', label: '에이전트 회의실' },
    { path: '/report', label: '리포트' },
    { path: '/risk-manage', label: '리스크 관리' },
    { path: '/mypage', label: '마이페이지' },
  ];

  const { latestEvent } = useOrderSSE();

  // 전역 체결 알림 수신 (ORDER_EXECUTED) — 토스트 + 알림 목록(EXECUTED 타입) 추가
  useEffect(() => {
    if (!latestEvent) return;
    if (latestEvent.type === 'ORDER_EXECUTED') {
      const stock = latestEvent.stockCode || '주문';
      const orderNo = latestEvent.kisOrderNo ?? '-';
      addNotification({
        type: 'EXECUTED',
        message: `${stock} 체결 완료`,
        description: `주문번호: ${orderNo}`,
      });
      toast.success(`${stock} 체결 완료`, {
        description: `주문번호: ${orderNo}`,
      });
    }
  }, [latestEvent, addNotification]);

  // 검색어 변경 시 API 호출
  useEffect(() => {
    if (!query.trim()) { setResults([]); setShowDropdown(false); return; }
    clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(async () => {
      try {
        // GET /api/v1/markets/stocks?keyword=... → { stocks, totalCount, page, size }
        const data = await getStocks({ keyword: query, page: 1, size: 20 });
        const stocks = data?.stocks ?? [];
        setResults(stocks);
        setShowDropdown(stocks.length > 0);
      } catch (error) {
        console.error('종목 검색 실패:', error);
        setResults([]);
        setShowDropdown(false);
      }
    }, 300);
    return () => clearTimeout(debounceRef.current);
  }, [query]);

  // 검색바 외부 클릭 시 드롭다운 닫기
  useEffect(() => {
    function handleClickOutside(e) {
      if (searchRef.current && !searchRef.current.contains(e.target)) {
        setShowDropdown(false);
      }
    }
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  // 알림 팝업 외부 클릭 시 닫기
  useEffect(() => {
    if (!isAlarmOpen) return;
    function handleClickOutside(e) {
      if (alarmRef.current && !alarmRef.current.contains(e.target)) {
        setIsAlarmOpen(false);
      }
    }
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [isAlarmOpen]);

  const handleReadAll = () => markAllAsRead();

  const handleOpenPendingModal = () => {
    setIsPendingModalOpen(true);
    setIsAlarmOpen(false); // 모달 열면 팝업은 자동 닫기
  };

  const handleSelect = (stock) => {
    setQuery('');
    setShowDropdown(false);
    navigate(`/trading?stock=${stock.stockCode}&name=${encodeURIComponent(stock.stockName)}`);
  };

  // 알림 timestamp(ISO) → 상대 시간/시각 표시
  const formatNotiTime = (iso) => {
    const t = new Date(iso).getTime();
    if (!Number.isFinite(t)) return '';
    const diffSec = Math.floor((Date.now() - t) / 1000);
    if (diffSec < 60) return '방금 전';
    if (diffSec < 3600) return `${Math.floor(diffSec / 60)}분 전`;
    if (diffSec < 86400) return `${Math.floor(diffSec / 3600)}시간 전`;
    const d = new Date(iso);
    return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  };

  return (
    <div className="main-layout">
      {/* 1. 사이드바 */}
      <aside className="sidebar">
        <div className="sidebar-logo">
          <Link to="/home" style={{ textDecoration: 'none', color: 'inherit' }}>
            <h2>MODU</h2>
          </Link>
        </div>
        <nav className="sidebar-nav">
          {menuItems.map((item) => (
            <Link
              key={item.path}
              to={item.path}
              className={`nav-item ${location.pathname.startsWith(item.path) ? 'active' : ''}`}
            >
              {item.icon}
              <span>{item.label}</span>
              {item.isNew && <span className="nav-new-badge">NEW</span>}
            </Link>
          ))}
        </nav>
      </aside>

      {/* 2. 메인 */}
      <main className="main-content">
        {/* 상단 통합 검색바 */}
        <header className="global-header">
          <div className="global-search-wrapper" ref={searchRef}>
            <div className="global-search-bar">
              <Search size={20} color="#888" />
              <input
                type="text"
                placeholder="원하는 종목을 검색해보세요!"
                value={query}
                onChange={e => setQuery(e.target.value)}
                onFocus={() => results.length > 0 && setShowDropdown(true)}
              />
            </div>
            {showDropdown && (
              <ul className="search-dropdown">
                {results.map(stock => (
                  <li key={stock.stockCode} className="search-dropdown-item" onMouseDown={() => handleSelect(stock)}>
                    <span className="sd-name">{stock.stockName}</span>
                    <span className="sd-code">{stock.stockCode}</span>
                    <span className="sd-code">{stock.marketType}</span>
                  </li>
                ))}
              </ul>
            )}
          </div>

          <div className="alarm-controls" ref={alarmRef}>
            <button
              className="global-notification-btn"
              aria-label="알림"
              onClick={() => setIsAlarmOpen(prev => !prev)}
            >
              <Bell size={20} />
              {bellBadgeCount > 0 && (
                <span className="alarm-badge">{bellBadgeCount > 99 ? '99+' : bellBadgeCount}</span>
              )}
            </button>

            {isAlarmOpen && (
              <div className="alarm-popup">
                <div className="alarm-popup-header">
                  <span>알림 목록</span>
                  {unreadCount > 0 && (
                    <button className="alarm-read-all" onClick={handleReadAll}>모두 읽음</button>
                  )}
                </div>

                {/* AI 승인 대기가 있을 때만 상단 강조 영역 */}
                {pendingCount > 0 && (
                  <button
                    type="button"
                    className="alarm-pending-banner"
                    onClick={handleOpenPendingModal}
                  >
                    <span className="alarm-pending-banner-icon">🚨</span>
                    <span className="alarm-pending-banner-text">
                      AI 승인 대기 <strong>{pendingCount}건</strong>
                    </span>
                    <span className="alarm-pending-banner-cta">확인 →</span>
                  </button>
                )}

                <div className="alarm-popup-list">
                  {notifications.length === 0 ? (
                    <div className="alarm-empty">알림이 없습니다.</div>
                  ) : (
                    notifications.map(n => {
                      const meta = NOTIFICATION_TYPE_META[n.type] ?? { label: '', icon: '🔔', color: '#888' };
                      return (
                        <div key={n.id} className={`alarm-item${n.isRead ? '' : ' unread'}`}>
                          <div className="alarm-item-content">
                            <span className="alarm-item-icon" style={{ color: meta.color }} aria-hidden="true">
                              {meta.icon}
                            </span>
                            <div className="alarm-item-text">
                              <div className="alarm-item-message">{n.message}</div>
                              {n.description && (
                                <div className="alarm-item-description">{n.description}</div>
                              )}
                            </div>
                          </div>
                          <div className="alarm-item-time">{formatNotiTime(n.timestamp)}</div>
                        </div>
                      );
                    })
                  )}
                </div>
              </div>
            )}
          </div>
        </header>

        <div className="page-content fade-in-page" key={location.pathname}>
          <Outlet />
        </div>
      </main>

      {/* AI 판단 승인 대기 모달 — Bell 팝업의 "확인" 버튼으로 토글 */}
      {isPendingModalOpen && (
        <PendingDecisionsModal onClose={() => setIsPendingModalOpen(false)} />
      )}
    </div>
  );
}
