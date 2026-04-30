import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { MessageCircle } from 'lucide-react';
import './LoginPage.css';

export default function LoginPage() {
  const navigate = useNavigate();
  const [userId, setUserId] = useState('');

  const handleSocialLogin = async (provider) => {
    console.log(`Connecting to ${provider} login...`);

    /* 
    // 실제 연동 시 주석 해제 필요 !!
    try {
      const response = await fetch(`/api/v1/auth/social/${provider}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
      });
      const data = await response.json();
      
      if (response.ok) {
        if (data.isRegistered) {
          // 로그인 성공 -> Access/Refresh 토큰 저장 후 메인 페이지로 이동
          // localStorage.setItem('accessToken', data.accessToken);
          navigate('/home');
        } else {
          // 신규 유저 -> tempToken 저장 후 온보딩으로 이동
          // sessionStorage.setItem('tempToken', data.tempToken);
          navigate('/onboarding'); // 온보딩 라우트로 변경 필요 ~
        }
      } else {
        alert('소셜 로그인 실패: ' + data.message);
      }
    } catch (error) {
      console.error('Login error:', error);
    }
    */

    // 임시 -> 클릭 시 메인으로
    navigate('/home');
  };

  const handleTestLogin = async (e) => {
    e.preventDefault();
    if (!userId.trim()) return;
    console.log(`Bypass login with UUID: ${userId}`);

    /*
    // 실제 연동 시 주석 해제 필요 !!
    try {
      const response = await fetch('/api/v1/auth/test/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: userId })
      });
      const data = await response.json();

      if (response.ok) {
        // 성공 시 토큰 저장 + 메인 페이지로 이동
        // localStorage.setItem('accessToken', data.accessToken);
        navigate('/home');
      } else {
        alert('우회 로그인 실패: ' + data.message);
      }
    } catch (error) {
      console.error('Test Login error:', error);
    }
    */

    // 임시 -> 클릭 시 메인으로
    navigate('/home');
  };

  return (
    <div className="login-container">
      <div className="login-card fade-in-section fade-in-visible">
        <h1 className="login-title">MODU</h1>
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
              id="userId"
              value={userId}
              onChange={(e) => setUserId(e.target.value)}
              placeholder="테스트 ID"
            />
            <button type="submit" className="test-login-btn">접속</button>
          </div>
        </form>
      </div>
    </div>
  );
}
