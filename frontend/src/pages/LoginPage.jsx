import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { MessageCircle } from 'lucide-react';
import './LoginPage.css';

export default function LoginPage() {
  const navigate = useNavigate();
  const [userId, setUserId] = useState('');

  const handleSocialLogin = (provider) => {
    console.log(`Connecting to ${provider} login...`);
    // 임시 -> 클릭 시 메인으로
    navigate('/home');
  };

  const handleTestLogin = (e) => {
    e.preventDefault();
    if (!userId.trim()) return;
    console.log(`Bypass login with UUID: ${userId}`);
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
