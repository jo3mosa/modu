import { useState, useEffect, useRef } from 'react';
import { Outlet, Link, useLocation, useNavigate } from 'react-router-dom';
import { Search, Bell } from 'lucide-react';
import { getStocks } from '../api/market';
import { useOrderSSE } from '../hooks/useOrderSSE';
import './MainLayout.css';

export default function MainLayout() {
  const location = useLocation();
  const navigate = useNavigate();

  const [query, setQuery] = useState('');
  const [results, setResults] = useState([]);
  const [showDropdown, setShowDropdown] = useState(false);
  const searchRef = useRef(null);
  const debounceRef = useRef(null);

  // 알림 상태
  const [isAlarmOpen, setIsAlarmOpen] = useState(false);
  const [notifications, setNotifications] = useState([]);
  const alarmRef = useRef(null);
  const unreadCount = notifications.filter(n => !n.isRead).length;

  const menuItems = [
    { path: '/home', label: '대시보드' },
    { path: '/trading', label: '트레이딩 룸' },
    { path: '/report', label: '리포트' },
    { path: '/risk-manage', label: '리스크 관리' },
    { path: '/mypage', label: '마이페이지' },
  ];

  const { latestEvent } = useOrderSSE();

  // 전역 체결 알림 수신 (ORDER_EXECUTED)
  useEffect(() => {
    if (!latestEvent) return;
    if (latestEvent.type === 'ORDER_EXECUTED') {
      const newNoti = {
        id: Date.now(),
        message: `[체결] ${latestEvent.stockCode || '주문'} 체결 완료! (주문번호: ${latestEvent.kisOrderNo})`,
        timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
        isRead: false
      };
      setNotifications(prev => [newNoti, ...prev]);
      // 추후 전역 Toast UI 적용
      alert(`[체결 알림] ${latestEvent.stockCode || '주문'} 체결 완료! (주문번호: ${latestEvent.kisOrderNo})`);
    }
  }, [latestEvent]);

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

  const handleReadAll = () => {
    setNotifications(prev => prev.map(n => ({ ...n, isRead: true })));
  };

  const handleSelect = (stock) => {
    setQuery('');
    setShowDropdown(false);
    navigate(`/trading?stock=${stock.stockCode}&name=${encodeURIComponent(stock.stockName)}`);
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
              {unreadCount > 0 && <span className="alarm-badge">{unreadCount}</span>}
            </button>

            {isAlarmOpen && (
              <div className="alarm-popup">
                <div className="alarm-popup-header">
                  <span>알림 목록</span>
                  {unreadCount > 0 && (
                    <button className="alarm-read-all" onClick={handleReadAll}>모두 읽음</button>
                  )}
                </div>
                <div className="alarm-popup-list">
                  {notifications.length === 0 ? (
                    <div className="alarm-empty">알림이 없습니다.</div>
                  ) : (
                    notifications.map(n => (
                      <div key={n.id} className={`alarm-item${n.isRead ? '' : ' unread'}`}>
                        <div className="alarm-item-content">{n.message}</div>
                        <div className="alarm-item-time">{n.timestamp}</div>
                      </div>
                    ))
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

      {/* 3. AI 채팅 -> 나중에 */}
    </div>
  );
}
