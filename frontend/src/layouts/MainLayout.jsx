import { useState, useEffect, useRef } from 'react';
import { Outlet, Link, useLocation, useNavigate } from 'react-router-dom';
import {
  Search,
  Bell,
  LayoutDashboard,
  TrendingUp,
  Bot,
  FileText,
  ShieldCheck,
  User,
  Menu,
  Compass
} from 'lucide-react';
import { toast } from 'sonner';
import { getStocks } from '../api/market';
import { logout } from '../api/auth';
import { deleteKisKey } from '../api/user';
import { useOrderSSE } from '../hooks/useOrderSSE';
import { useNotifications, NOTIFICATION_TYPE_META } from '../hooks/useNotifications';
import { usePendingDecisions } from '../hooks/usePendingDecisions';
import PendingDecisionsModal from '../components/PendingDecisionsModal';
import ConfirmDialog from '../components/ConfirmDialog';
import './MainLayout.css';

// 주요 60대 대형주 정적 폴백 사전 (초성 즉각 검색 및 API timing 이슈 방어)
const MAJOR_STOCKS = [
  { stockCode: '005930', stockName: '삼성전자', marketType: 'KOSPI' },
  { stockCode: '000660', stockName: 'SK하이닉스', marketType: 'KOSPI' },
  { stockCode: '035720', stockName: '카카오', marketType: 'KOSPI' },
  { stockCode: '035420', stockName: 'NAVER', marketType: 'KOSPI' },
  { stockCode: '035420', stockName: '네이버', marketType: 'KOSPI' },
  { stockCode: '005380', stockName: '현대차', marketType: 'KOSPI' },
  { stockCode: '000270', stockName: '기아', marketType: 'KOSPI' },
  { stockCode: '373220', stockName: 'LG에너지솔루션', marketType: 'KOSPI' },
  { stockCode: '207940', stockName: '삼성바이오로직스', marketType: 'KOSPI' },
  { stockCode: '006400', stockName: '삼성SDI', marketType: 'KOSPI' },
  { stockCode: '051910', stockName: 'LG화학', marketType: 'KOSPI' },
  { stockCode: '005490', stockName: 'POSCO홀딩스', marketType: 'KOSPI' },
  { stockCode: '005490', stockName: '포스코홀딩스', marketType: 'KOSPI' },
  { stockCode: '068270', stockName: '셀트리온', marketType: 'KOSPI' },
  { stockCode: '005935', stockName: '삼성전자우', marketType: 'KOSPI' },
  { stockCode: '003550', stockName: 'LG', marketType: 'KOSPI' },
  { stockCode: '032830', stockName: '삼성생명', marketType: 'KOSPI' },
  { stockCode: '015760', stockName: '한국전력', marketType: 'KOSPI' },
  { stockCode: '000810', stockName: '삼성화재', marketType: 'KOSPI' },
  { stockCode: '033780', stockName: 'KT&G', marketType: 'KOSPI' },
  { stockCode: '009150', stockName: '삼성전기', marketType: 'KOSPI' },
  { stockCode: '010950', stockName: 'S-Oil', marketType: 'KOSPI' },
  { stockCode: '086790', stockName: '하나금융지주', marketType: 'KOSPI' },
  { stockCode: '055550', stockName: '신한지주', marketType: 'KOSPI' },
  { stockCode: '105560', stockName: 'KB금융', marketType: 'KOSPI' },
  { stockCode: '030200', stockName: 'KT', marketType: 'KOSPI' },
  { stockCode: '017670', stockName: 'SK텔레콤', marketType: 'KOSPI' },
  { stockCode: '032640', stockName: 'LG유플러스', marketType: 'KOSPI' },
  { stockCode: '011200', stockName: 'HMM', marketType: 'KOSPI' },
  { stockCode: '323410', stockName: '카카오뱅크', marketType: 'KOSPI' },
  { stockCode: '377300', stockName: '카카오페이', marketType: 'KOSPI' },
  { stockCode: '259960', stockName: '크래프톤', marketType: 'KOSPI' },
  { stockCode: '036570', stockName: '엔씨소프트', marketType: 'KOSPI' },
  { stockCode: '090430', stockName: '아모레퍼시픽', marketType: 'KOSPI' },
  { stockCode: '097950', stockName: 'CJ제일제당', marketType: 'KOSPI' },
  { stockCode: '000100', stockName: '유한양행', marketType: 'KOSPI' },
  { stockCode: '000720', stockName: '현대건설', marketType: 'KOSPI' },
  { stockCode: '010140', stockName: '삼성중공업', marketType: 'KOSPI' },
  { stockCode: '009540', stockName: 'HD현대중공업', marketType: 'KOSPI' },
  { stockCode: '042700', stockName: '한미반도체', marketType: 'KOSPI' },
  { stockCode: '138040', stockName: '메리츠금융지주', marketType: 'KOSPI' },
  { stockCode: '192820', stockName: '코스맥스', marketType: 'KOSPI' },
  // 코스닥 주요 종목
  { stockCode: '247540', stockName: '에코프로비엠', marketType: 'KOSDAQ' },
  { stockCode: '086520', stockName: '에코프로', marketType: 'KOSDAQ' },
  { stockCode: '397600', stockName: 'L&F', marketType: 'KOSDAQ' },
  { stockCode: '028300', stockName: 'HLB', marketType: 'KOSDAQ' },
  { stockCode: '214150', stockName: '클래시스', marketType: 'KOSDAQ' },
  { stockCode: '036830', stockName: '솔브레인', marketType: 'KOSDAQ' },
  { stockCode: '035900', stockName: 'JYP Ent.', marketType: 'KOSDAQ' },
  { stockCode: '253450', stockName: '스튜디오드래곤', marketType: 'KOSDAQ' },
  { stockCode: '195870', stockName: '해성디에스', marketType: 'KOSDAQ' },
  { stockCode: '058470', stockName: '리노공업', marketType: 'KOSDAQ' },
  { stockCode: '293490', stockName: '카카오게임즈', marketType: 'KOSDAQ' },
  { stockCode: '112040', stockName: '위메이드', marketType: 'KOSDAQ' },
  { stockCode: '263750', stockName: '펄어비스', marketType: 'KOSDAQ' },
];

// 한글 초성 추출 유틸리티
function getChoseong(str) {
  const cho = ["ㄱ","ㄲ","ㄴ","ㄷ","ㄸ","ㄹ","ㅁ","ㅂ","ㅃ","ㅅ","ㅆ","ㅇ","ㅈ","ㅉ","ㅊ","ㅋ","ㅌ","ㅍ","ㅎ"];
  let result = "";
  for (let i = 0; i < str.length; i++) {
    const code = str.charCodeAt(i) - 44032;
    if (code > -1 && code < 11172) {
      result += cho[Math.floor(code / 588)];
    } else {
      result += str.charAt(i);
    }
  }
  return result;
}

export default function MainLayout() {
  const location = useLocation();
  const navigate = useNavigate();

  const [query, setQuery] = useState('');
  const [results, setResults] = useState([]);
  const [showDropdown, setShowDropdown] = useState(false);
  const [allStocks, setAllStocks] = useState(MAJOR_STOCKS); // 대형주 사전으로 즉시 초기화
  const [focusedIndex, setFocusedIndex] = useState(0);
  const searchRef = useRef(null);

  // 알림 팝업 / 승인 모달 토글
  const [isAlarmOpen, setIsAlarmOpen] = useState(false);
  const [isPendingModalOpen, setIsPendingModalOpen] = useState(false);
  const [isProfileOpen, setIsProfileOpen] = useState(false);
  const [confirmState, setConfirmState] = useState(null);
  const alarmRef = useRef(null);
  const profileDropdownRef = useRef(null);

  // 전역 알림 + 승인 대기 — Provider에서 제공
  const { notifications, unreadCount, addNotification, markAllAsRead } = useNotifications();
  const { pending } = usePendingDecisions();
  const pendingCount = pending.length;
  // Bell 뱃지 — 안 읽은 알림 + 승인 대기 합산 (사용자 인지 영역 통합)
  const bellBadgeCount = unreadCount + pendingCount;

  // 사이드바 축소/최소화 상태 관리 (새로고침 시에도 유지)
  const [isSidebarCollapsed, setIsSidebarCollapsed] = useState(() => {
    return localStorage.getItem('modu.sidebarCollapsed') === 'true';
  });

  const toggleSidebar = () => {
    setIsSidebarCollapsed((prev) => {
      const next = !prev;
      localStorage.setItem('modu.sidebarCollapsed', String(next));
      return next;
    });

    // 300ms 트랜지션 동안 실시간으로 window resize 이벤트를 발생시켜 차트가 실시간으로 유동적으로 리사이징되도록 함!
    const startTime = performance.now();
    const duration = 350; // 트랜지션 지속 시간(300ms)보다 약간 여유 있게 설정

    function animateResize(time) {
      window.dispatchEvent(new Event('resize'));
      if (time - startTime < duration) {
        requestAnimationFrame(animateResize);
      }
    }
    requestAnimationFrame(animateResize);
  };

  const menuItems = [
    { path: '/home', label: '대시보드', icon: <LayoutDashboard size={20} /> },
    { path: '/trading', label: '트레이딩 룸', icon: <TrendingUp size={20} /> },
    { path: '/discovery', label: '종목 추천', icon: <Compass size={20} /> },
    { path: '/agent-meeting', label: '에이전트 회의실', icon: <Bot size={20} /> },
    { path: '/report', label: '리포트', icon: <FileText size={20} /> },
    { path: '/risk-manage', label: '리스크 관리', icon: <ShieldCheck size={20} /> },
    { path: '/mypage', label: '마이페이지', icon: <User size={20} /> },
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

  // 컴포넌트 마운트 시 전체 종목 사전 로드
  useEffect(() => {
    async function loadAllStocks() {
      try {
        const data = await getStocks({ page: 1, size: 100 });
        const list = data?.stocks ?? [];
        if (list.length > 0) {
          // 중복을 제거하며 대형주 사전과 병합
          const uniqueMap = new Map();
          MAJOR_STOCKS.forEach(s => uniqueMap.set(s.stockCode, s));
          list.forEach(s => uniqueMap.set(s.stockCode, s));
          setAllStocks(Array.from(uniqueMap.values()));
        }
      } catch (error) {
        console.warn('전체 종목 사전 로드 실패:', error);
      }
    }
    loadAllStocks();
  }, []);

  // 검색창 포커스 시/캐시가 부족할 때 보완 로드 (On-Focus Fetch)
  const handleFocus = async () => {
    if (query.trim()) {
      setShowDropdown(results.length > 0);
    }
    if (allStocks.length <= MAJOR_STOCKS.length) {
      try {
        const data = await getStocks({ page: 1, size: 100 });
        const list = data?.stocks ?? [];
        if (list.length > 0) {
          const uniqueMap = new Map();
          MAJOR_STOCKS.forEach(s => uniqueMap.set(s.stockCode, s));
          list.forEach(s => uniqueMap.set(s.stockCode, s));
          setAllStocks(Array.from(uniqueMap.values()));
        }
      } catch (error) {
        console.warn('전체 종목 포커싱 로드 실패:', error);
      }
    }
  };

  // 검색어 변경 시 하이브리드 필터링 (초성 로컬 매칭 + 일반 텍스트 백엔드 쿼리)
  useEffect(() => {
    const trimmed = query.trim().toLowerCase();
    if (!trimmed) {
      setResults([]);
      setShowDropdown(false);
      setFocusedIndex(0);
      return;
    }

    const isChoseongQuery = /^[ㄱ-ㅎ\s]+$/.test(trimmed);

    if (isChoseongQuery) {
      // 1. 초성 검색: 로컬 병합 캐시에서 즉시 필터링 (0ms 반응)
      const queryCho = getChoseong(trimmed);
      const filtered = allStocks.filter((s) => {
        const name = (s.stockName ?? '').toLowerCase();
        const code = (s.stockCode ?? '').toLowerCase();
        const nameCho = getChoseong(name);
        return name.includes(trimmed) || code.includes(trimmed) || nameCho.includes(queryCho);
      });

      setResults(filtered.slice(0, 8));
      setShowDropdown(filtered.length > 0);
      setFocusedIndex(0);
    } else {
      // 2. 일반 텍스트 검색: 백엔드 동적 API 쿼리 (200ms 디바운스로 API 부담 경감)
      const handler = setTimeout(async () => {
        try {
          const data = await getStocks({ keyword: trimmed, page: 1, size: 20 });
          const list = data?.stocks ?? [];

          // 로컬 매칭 목록 추출하여 API 결과와 병합 (매끄러운 UX 확보)
          const localFiltered = allStocks.filter((s) => {
            const name = (s.stockName ?? '').toLowerCase();
            const code = (s.stockCode ?? '').toLowerCase();
            return name.includes(trimmed) || code.includes(trimmed);
          });

          const uniqueMap = new Map();
          localFiltered.forEach(s => uniqueMap.set(s.stockCode, s));
          list.forEach(s => uniqueMap.set(s.stockCode, s));

          const merged = Array.from(uniqueMap.values()).slice(0, 8);
          setResults(merged);
          setShowDropdown(merged.length > 0);
          setFocusedIndex(0);
        } catch (error) {
          console.warn('동적 종목 검색 실패:', error);
          // API 실패 시 로컬 검색으로 안전 폴백
          const localFiltered = allStocks.filter((s) => {
            const name = (s.stockName ?? '').toLowerCase();
            const code = (s.stockCode ?? '').toLowerCase();
            return name.includes(trimmed) || code.includes(trimmed);
          });
          const merged = localFiltered.slice(0, 8);
          setResults(merged);
          setShowDropdown(merged.length > 0);
          setFocusedIndex(0);
        }
      }, 200);

      return () => clearTimeout(handler);
    }
  }, [query, allStocks]);

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

  // 프로필 드롭다운 외부 클릭 시 닫기
  useEffect(() => {
    if (!isProfileOpen) return;
    function handleClickOutside(e) {
      if (profileDropdownRef.current && !profileDropdownRef.current.contains(e.target)) {
        setIsProfileOpen(false);
      }
    }
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [isProfileOpen]);

  const handleLogout = async () => {
    try {
      await logout();
    } catch (e) {
      console.error('로그아웃 실패:', e);
    } finally {
      localStorage.removeItem('modu.token');
      localStorage.removeItem('modu.user');
      toast.success('로그아웃되었습니다');
      navigate('/login');
    }
  };

  const handleDisconnectKis = async () => {
    try {
      await deleteKisKey();
      toast.success('한국투자증권 연동이 해제되었습니다');
      setIsProfileOpen(false);
      if (location.pathname === '/mypage') {
        window.location.reload();
      }
    } catch (e) {
      console.error('연동 해제 실패:', e);
      toast.error(e.message || '연동 해제에 실패했습니다.');
    }
  };

  const triggerLogoutConfirm = () => {
    setIsProfileOpen(false);
    setConfirmState({
      title: '로그아웃',
      message: '정말 로그아웃하시겠습니까?',
      confirmLabel: '로그아웃',
      cancelLabel: '취소',
      variant: 'danger',
      onConfirm: handleLogout,
    });
  };

  const triggerDisconnectConfirm = () => {
    setIsProfileOpen(false);
    setConfirmState({
      title: '연동 해제',
      message: '한국투자증권 연동을 해제하시겠습니까?',
      description: '자동매매가 중단되며 자산/주문 조회도 불가능해집니다.',
      confirmLabel: '연동 해제',
      cancelLabel: '닫기',
      variant: 'danger',
      onConfirm: handleDisconnectKis,
    });
  };

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

  const handleKeyDown = (e) => {
    if (!showDropdown || results.length === 0) return;

    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setFocusedIndex((prev) => (prev + 1) % results.length);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setFocusedIndex((prev) => (prev - 1 + results.length) % results.length);
    } else if (e.key === 'Enter') {
      e.preventDefault();
      if (results[focusedIndex]) {
        handleSelect(results[focusedIndex]);
      }
    } else if (e.key === 'Escape') {
      setShowDropdown(false);
    }
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
    <div className={`main-layout ${isSidebarCollapsed ? 'sidebar-collapsed' : ''}`}>
      {/* 1. 사이드바 */}
      <aside className="sidebar">
        <div className="sidebar-logo">
          <button className="sidebar-menu-btn" onClick={toggleSidebar} aria-label="사이드바 토글">
            <Menu size={22} />
          </button>
          {!isSidebarCollapsed && (
            <Link to="/home" style={{ textDecoration: 'none', color: 'inherit' }} className="sidebar-logo-text">
              <h2 className="brand-font">MODU</h2>
            </Link>
          )}
        </div>
        <nav className="sidebar-nav">
          {menuItems.map((item) => (
            <Link
              key={item.path}
              to={item.path}
              className={`nav-item ${location.pathname.startsWith(item.path) ? 'active' : ''}`}
              title={isSidebarCollapsed ? item.label : undefined}
            >
              {item.icon}
              <span className="nav-label">{item.label}</span>
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
                onFocus={handleFocus}
                onKeyDown={handleKeyDown}
              />
            </div>
            {showDropdown && (
              <ul className="search-dropdown">
                {results.map((stock, idx) => (
                  <li
                    key={stock.stockCode}
                    className={`search-dropdown-item ${idx === focusedIndex ? 'focused' : ''}`}
                    onMouseDown={() => handleSelect(stock)}
                    onMouseEnter={() => setFocusedIndex(idx)}
                  >
                    <span className="sd-name">{stock.stockName}</span>
                    <span className="sd-code">{stock.stockCode}</span>
                    <span className="sd-code">{stock.marketType}</span>
                  </li>
                ))}
              </ul>
            )}
          </div>

          <div className="header-right-actions">
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
                        const meta = NOTIFICATION_TYPE_META[n.type] ?? { label: '', color: '#888' };
                        return (
                          <div key={n.id} className={`alarm-item${n.isRead ? '' : ' unread'}`}>
                            <div className="alarm-item-content">
                              <span className="alarm-item-type-tag" style={{ color: meta.color, borderColor: meta.color }}>
                                {meta.label}
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

            <div className="profile-controls" ref={profileDropdownRef}>
              <button
                className="global-profile-btn"
                aria-label="마이페이지"
                onClick={() => setIsProfileOpen(prev => !prev)}
              >
                <User size={20} />
              </button>

              {isProfileOpen && (
                <div className="profile-popup">
                  <div className="profile-popup-header">
                    <span>내 계정</span>
                  </div>
                  <div className="profile-popup-list">
                    <button
                      className="profile-popup-item"
                      onClick={() => {
                        navigate('/mypage');
                        setIsProfileOpen(false);
                      }}
                    >
                      마이페이지
                    </button>
                    <button
                      className="profile-popup-item disconnect"
                      onClick={triggerDisconnectConfirm}
                    >
                      연동 해제
                    </button>
                    <button
                      className="profile-popup-item logout"
                      onClick={triggerLogoutConfirm}
                    >
                      로그아웃
                    </button>
                  </div>
                </div>
              )}
            </div>
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

      {/* 커스텀 확인 다이얼로그 */}
      <ConfirmDialog
        open={!!confirmState}
        {...(confirmState ?? {})}
        onClose={() => setConfirmState(null)}
      />
    </div>
  );
}
