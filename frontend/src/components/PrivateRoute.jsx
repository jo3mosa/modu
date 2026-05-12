import { useEffect, useState } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { getAccessToken, refreshAccessToken } from '../api/apiClient';

const STATUS = {
  CHECKING: 'CHECKING',
  AUTHENTICATED: 'AUTHENTICATED',
  UNAUTHENTICATED: 'UNAUTHENTICATED',
};

/**
 * 인증 가드.
 *
 * - 메모리에 accessToken이 있으면 즉시 통과 (정상 로그인 직후 / 페이지 이동)
 * - 토큰이 없으면 refreshToken 쿠키로 재발급 시도 (새로고침 직후 시나리오)
 * - 재발급 실패 시 /login으로 이동, 이전 URL은 state.from으로 보존
 */
export default function PrivateRoute({ children }) {
  const location = useLocation();
  const [status, setStatus] = useState(() =>
    getAccessToken() ? STATUS.AUTHENTICATED : STATUS.CHECKING
  );

  useEffect(() => {
    if (status !== STATUS.CHECKING) return;
    let cancelled = false;

    (async () => {
      try {
        await refreshAccessToken();
        if (!cancelled) setStatus(STATUS.AUTHENTICATED);
      } catch {
        if (!cancelled) setStatus(STATUS.UNAUTHENTICATED);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [status]);

  if (status === STATUS.CHECKING) {
    return (
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          minHeight: '100vh',
          backgroundColor: '#0d0d0d',
          color: '#aaa',
          fontSize: '0.95rem',
        }}
      >
        인증 정보를 확인하는 중…
      </div>
    );
  }

  if (status === STATUS.UNAUTHENTICATED) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }

  return children;
}
