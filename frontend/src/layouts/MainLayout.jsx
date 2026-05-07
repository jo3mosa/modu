import { useState, useEffect, useRef } from 'react';
import { Outlet, Link, useLocation, useNavigate } from 'react-router-dom';
import { Search } from 'lucide-react';
import { getStocks } from '../api/market';
import './MainLayout.css';

export default function MainLayout() {
  const location = useLocation();
  const navigate = useNavigate();

  const [query, setQuery] = useState('');
  const [results, setResults] = useState([]);
  const [showDropdown, setShowDropdown] = useState(false);
  const searchRef = useRef(null);
  const debounceRef = useRef(null);

  const menuItems = [
    { path: '/home',        label: '대시보드' },
    { path: '/trading',     label: '트레이딩 룸' },
    { path: '/report',      label: '리포트' },
    { path: '/risk-manage', label: '리스크 관리' },
    { path: '/mypage',      label: '마이페이지' },
  ];

  // 검색어 변경 시 API 호출 (300ms debounce)
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
        </header>

        <div className="page-content fade-in-page" key={location.pathname}>
          <Outlet />
        </div>
      </main>

      {/* 3. AI 채팅 -> 나중에 */}
    </div>
  );
}
