import { useState } from 'react';
import { Eye, EyeOff } from 'lucide-react';
import './MyPage.css';

// 더미
const MOCK_PROFILE = {
  name: '김모두',
  email: 'modu@kakao.com',
  provider: 'Kakao',
  joinedAt: '2026.5.2'
};

const MOCK_KIS_STATUS = {
  isConnected: true,
  appKey: 'xxx',
  appSecret: 'xxx'
};

export default function MyPage() {
  const [profile] = useState(MOCK_PROFILE);
  const [apiKeys, setApiKeys] = useState({
    appKey: MOCK_KIS_STATUS.appKey,
    appSecret: MOCK_KIS_STATUS.appSecret
  });
  const [isConnected, setIsConnected] = useState(MOCK_KIS_STATUS.isConnected);
  const [showSecret, setShowSecret] = useState(false);

  const [settings, setSettings] = useState({
    tradeNoti: true,
    riskNoti: true
  });

  const handleSaveKeys = () => {
    alert('한국투자증권 API 키가 저장되었습니다!');
    setIsConnected(true);
  };

  const handleToggleSetting = (key) => {
    setSettings(prev => ({ ...prev, [key]: !prev[key] }));
  };

  return (
    <div className="mypage-container" style={{ padding: '0 0.5rem' }}>
      <div className="page-header-container">
        <div className="page-title-group">
          <h1>마이페이지</h1>
          <p>내 계정 정보와 한국투자증권 연동 설정을 관리하세요.</p>
        </div>
      </div>

      {/* 1. 카카오 프로필 */}
      <div className="mypage-panel">
        <h2>내 프로필</h2>
        <div className="profile-info">
          <div className="info-row">
            <span className="info-label">이름</span>
            <span className="info-value">{profile.name}</span>
          </div>
          <div className="info-row">
            <span className="info-label">연동 계정</span>
            <span className="info-value">{profile.email} ({profile.provider})</span>
          </div>
          <div className="info-row">
            <span className="info-label">가입일</span>
            <span className="info-value">{profile.joinedAt}</span>
          </div>
        </div>
      </div>

      {/* 2. KIS 연동 관리 */}
      <div className="mypage-panel">
        <h2>
          한국투자증권 연동 관리
          <span className={`status-badge ${isConnected ? 'connected' : 'disconnected'}`}>
            {isConnected ? '연결됨 🟢' : '미연결 🔴'}
          </span>
        </h2>
        <div className="api-form">
          <div className="input-group">
            <label>App Key</label>
            <input
              type="text"
              value={apiKeys.appKey}
              onChange={(e) => setApiKeys({ ...apiKeys, appKey: e.target.value })}
              placeholder="한국투자증권 App Key를 입력하세요"
            />
          </div>
          <div className="input-group">
            <label>App Secret</label>
            <div className="password-input-wrapper">
              <input
                type={showSecret ? "text" : "password"}
                value={apiKeys.appSecret}
                onChange={(e) => setApiKeys({ ...apiKeys, appSecret: e.target.value })}
                placeholder="한국투자증권 App Secret을 입력하세요"
              />
              <button
                type="button"
                className="eye-btn"
                onClick={() => setShowSecret(!showSecret)}
              >
                {showSecret ? <EyeOff size={20} /> : <Eye size={20} />}
              </button>
            </div>
          </div>
          <button className="save-btn" onClick={handleSaveKeys}>
            연동 테스트 및 저장
          </button>
        </div>
      </div>

      {/* 3. 알림 및 환경 설정 */}
      <div className="mypage-panel">
        <h2>알림 및 환경 설정</h2>
        <div className="settings-list">
          <div className="setting-item">
            <div className="setting-info">
              <h4>매매 체결 알림</h4>
              <p>AI가 매수·매도를 체결했을 때 브라우저 알림을 받습니다.</p>
            </div>
            <div
              className={`toggle-switch ${settings.tradeNoti ? 'on' : 'off'}`}
              onClick={() => handleToggleSetting('tradeNoti')}
            >
              <div className="toggle-knob" />
            </div>
          </div>

          <div className="setting-item">
            <div className="setting-info">
              <h4>위험 감지 긴급 알림</h4>
              <p>시장의 급격한 하락이나 비정상적인 손실 감지 시 알림을 받습니다.</p>
            </div>
            <div
              className={`toggle-switch ${settings.riskNoti ? 'on' : 'off'}`}
              onClick={() => handleToggleSetting('riskNoti')}
            >
              <div className="toggle-knob" />
            </div>
          </div>
        </div>
      </div>

      <div className="mypage-panel account-actions" style={{ background: 'transparent', border: 'none', boxShadow: 'none', padding: 0 }}>
        <button className="logout-btn" onClick={() => alert('로그아웃 되었습니다.')}>
          로그아웃
        </button>
        <button className="withdraw-btn" onClick={() => {
          if (window.confirm('정말 회원탈퇴를 진행하시겠습니까?\n가지마세요 ㅜㅜ')) {
            alert('회원탈퇴가 완료되었습니다.');
          }
        }}>
          회원탈퇴
        </button>
      </div>

    </div>
  );
}
