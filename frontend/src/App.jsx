import { BrowserRouter, Routes, Route } from 'react-router-dom';
import AuthLayout from './layouts/AuthLayout';
import MainLayout from './layouts/MainLayout';
import LandingPage from './pages/LandingPage';
import DashboardPage from './pages/DashboardPage';
import TradingPage from './pages/TradingPage';
import ReportPage from './pages/ReportPage';
import RiskManagePage from './pages/RiskManagePage';
import MyPage from './pages/MyPage';

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<AuthLayout />}>
          <Route path="/" element={<LandingPage />} />
        </Route>
        
        <Route element={<MainLayout />}>
          <Route path="/home" element={<DashboardPage />} />
          <Route path="/trading" element={<TradingPage />} />
          <Route path="/report" element={<ReportPage />} />
          <Route path="/risk-manage" element={<RiskManagePage />} />
          <Route path="/mypage" element={<MyPage />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}

export default App;
