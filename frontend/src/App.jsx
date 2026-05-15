import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { Toaster } from 'sonner';
import AuthLayout from './layouts/AuthLayout';
import MainLayout from './layouts/MainLayout';
import { OrderSSEProvider } from './hooks/useOrderSSE';
import PrivateRoute from './components/PrivateRoute';
import LandingPage from './pages/LandingPage';
import LoginPage from './pages/LoginPage';
import KakaoCallbackPage from './pages/KakaoCallbackPage';
import OnboardingPage from './pages/OnboardingPage';
import DashboardPage from './pages/DashboardPage';
import TradingPage from './pages/TradingPage';
import ReportPage from './pages/ReportPage';
import RiskManagePage from './pages/RiskManagePage';
import MyPage from './pages/MyPage';

function App() {
  return (
    <BrowserRouter>
      {/* 전역 토스트 — sonner. 우측 상단, 성공/실패 색상 구분, 기본 표시 시간 3초 */}
      <Toaster position="top-right" richColors closeButton duration={3000} />
      <Routes>
        <Route element={<AuthLayout />}>
          <Route path="/" element={<LandingPage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/auth/callback/kakao" element={<KakaoCallbackPage />} />
          <Route
            path="/onboarding"
            element={
              <PrivateRoute>
                <OnboardingPage />
              </PrivateRoute>
            }
          />
        </Route>

        <Route
          element={
            <PrivateRoute>
              <OrderSSEProvider>
                <MainLayout />
              </OrderSSEProvider>
            </PrivateRoute>
          }
        >
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
