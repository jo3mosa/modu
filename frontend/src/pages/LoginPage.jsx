import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { MessageCircle } from 'lucide-react';
import { testLogin } from '../api/auth';
import './LoginPage.css';

export default function LoginPage() {
  const navigate = useNavigate();
  const [userId, setUserId] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [errorMsg, setErrorMsg] = useState('');

  const handleSocialLogin = (provider) => {
    if (provider === 'kakao') {
      const clientId = import.meta.env.VITE_KAKAO_CLIENT_ID;
      // env 미설정 시 카카오 인증 페이지에서 에러로 직행하지 않도록 사전 차단
      if (!clientId || clientId === 'YOUR_KAKAO_REST_API_KEY') {
        setErrorMsg('카카오 로그인이 설정되지 않았습니다. 관리자에게 문의해주세요.');
        return;
      }
      const redirectUri = `${window.location.origin}/auth/callback/kakao`;
      const kakaoAuthUrl = `https://kauth.kakao.com/oauth/authorize?client_id=${clientId}&redirect_uri=${redirectUri}&response_type=code`;
      window.location.href = kakaoAuthUrl;
    }
  };

  const handleTestLogin = async (e) => {
    e.preventDefault();
    if (!userId.trim()) {
      setErrorMsg('사용자 ID를 입력해주세요.');
      return;
    }

    setIsLoading(true);
    setErrorMsg('');

    try {
      // POST /api/v1/auth/test/login
      // 로컬 환경(Spring Profile: local)에서만 동작
      const loginData = await testLogin(Number(userId));
      console.log('테스트 로그인 성공 - 온보딩 상태:', loginData.onboarding);

      // 응답의 onboarding 상태에 따라 라우팅 분기
      const { isSurveyCompleted, isRuleSetCompleted } = loginData.onboarding;
      if (isSurveyCompleted && isRuleSetCompleted) {
        navigate('/home');       // 온보딩 완료 → 대시보드
      } else {
        navigate('/onboarding'); // 온보딩 미완료 → 온보딩 페이지
      }
    } catch (error) {
      setErrorMsg(error.message || '로그인에 실패했습니다. 사용자 ID를 확인해주세요.');
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="login-container">
      <div className="login-card fade-in-section fade-in-visible">
        <h1 className="login-title brand-font">MODU</h1>
        <p className="login-subtitle">당신의 투자를 모두와 함께.</p>

        <div className="social-login-group">
          <button
            className="social-btn kakao-btn"
            onClick={() => handleSocialLogin('kakao')}
          >
            <MessageCircle size={20} className="kakao-icon" />
            카카오로 시작하기
          </button>
        </div>

        <div className="divider">
          <span>또는</span>
        </div>

        <form className="test-login-form" onSubmit={handleTestLogin}>
          <label htmlFor="userId">개발용 우회 로그인</label>
          <div className="input-group">
            <input
              type="text"
              inputMode="numeric"
              pattern="[0-9]*"
              id="userId"
              value={userId}
              onChange={(e) => setUserId(e.target.value)}
              placeholder="테스트 User ID (숫자)"
            />
            <button type="submit" className="test-login-btn" disabled={isLoading}>
              {isLoading ? '접속 중...' : '접속'}
            </button>
          </div>
          {errorMsg && <p className="login-error-msg" role="alert">{errorMsg}</p>}
        </form>
      </div>
    </div>
  );
}
