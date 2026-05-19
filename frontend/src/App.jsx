import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { Toaster } from 'sonner';
import AuthLayout from './layouts/AuthLayout';
import MainLayout from './layouts/MainLayout';
import { OrderSSEProvider } from './hooks/useOrderSSE';
import { AgentChatProvider } from './hooks/useAgentChat';
import { NotificationsProvider } from './hooks/useNotifications';
import { PendingDecisionsProvider } from './hooks/usePendingDecisions';
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
import AgentMeetingPage from './pages/AgentMeetingPage';
import DiscoveryPage from './pages/DiscoveryPage';

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
              {/* 알림 → SSE → AgentChat → PendingDecisions 순서 중첩.
                  AgentChat 은 OrderSSE 의 latestAgentMessage 를 구독하므로 그 안쪽.
                  PendingDecisions가 useNotifications 사용하므로 가장 안쪽에 둠. */}
              <NotificationsProvider>
                <OrderSSEProvider>
                  <AgentChatProvider>
                    <PendingDecisionsProvider>
                      <MainLayout />
                    </PendingDecisionsProvider>
                  </AgentChatProvider>
                </OrderSSEProvider>
              </NotificationsProvider>
            </PrivateRoute>
          }
        >
          <Route path="/home" element={<DashboardPage />} />
          <Route path="/trading" element={<TradingPage />} />
          <Route path="/discovery" element={<DiscoveryPage />} />
          <Route path="/agent-meeting" element={<AgentMeetingPage />} />
          <Route path="/report" element={<ReportPage />} />
          <Route path="/risk-manage" element={<RiskManagePage />} />
          <Route path="/mypage" element={<MyPage />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}

export default App;
