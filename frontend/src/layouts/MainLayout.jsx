import { Outlet, Link, useLocation } from 'react-router-dom';
import { LayoutDashboard, Activity, Newspaper, FileText, User, Search } from 'lucide-react';
import './MainLayout.css';

export default function MainLayout() {
  const location = useLocation();

  const menuItems = [
    { path: '/home', label: '대시보드' },
    { path: '/trading', label: '트레이딩 룸' },
    { path: '/report', label: '리포트' },
    { path: '/risk-manage', label: '리스크 관리' },
    { path: '/mypage', label: '마이페이지' },
  ];

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
          <div className="global-search-bar">
            <Search size={20} color="#888" />
            <input
              type="text"
              placeholder="원하는 종목을 검색해보세요!"
            />
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
