import { useState } from 'react';
import { Eye, EyeOff } from 'lucide-react';
// TODO: 백엔드 연동 시 아래 주석 해제
// import { registerKisKey, updateKisKey, deleteKisKey } from '../api/user';
// import { logout } from '../api/auth';
import './MyPage.css';

// ── MOCK 데이터 (백엔드 연동 후 제거 예정) ──────────────────────────────────
const MOCK_PROFILE = {
  name: '김싸피',
  email: 'modu@kakao.com',
  provider: 'Kakao',
  joinedAt: '2026.5.2'
};

const MOCK_KIS_STATUS = {
  isConnected: true,
  appKey: 'xxx',
  appSecret: 'xxx'
};
// ────────────────────────────────────────────────────────────────────────

export default function MyPage() {
  const [profile] = useState(MOCK_PROFILE);
  const [apiKeys, setApiKeys] = useState({
    appKey: MOCK_KIS_STATUS.appKey,
    appSecret: MOCK_KIS_STATUS.appSecret,
    // accountNo: '계좌번호-상품코드' 형식 (예: '50012345-01')
    // TODO: 백엔드에서 KIS 코 조회 API 생기면 여기로 할당
    accountNo: '',
  });
  const [isConnected, setIsConnected] = useState(MOCK_KIS_STATUS.isConnected);
  const [showSecret, setShowSecret] = useState(false);
  const [kisLoading, setKisLoading] = useState(false);
  const [kisError, setKisError] = useState('');

  const [settings, setSettings] = useState({
    tradeNoti: true,
    riskNoti: true
  });

  const handleSaveKeys = async () => {
    setKisError('');
    setKisLoading(true);

    // ── TODO: 백엔드 연동 시 아래 주석 해제 ──────────────────────────────
    // const payload = {
    //   appKey: apiKeys.appKey,
    //   appSecret: apiKeys.appSecret,
    //   accountNo: apiKeys.accountNo,  // '계좌번호-상품코드' 형식 (예: '50012345-01')
    // };
    // try {
    //   if (isConnected) {
    //     // 이미 연동된 경우 → PATCH 수정
    //     // PATCH /api/v1/users/me/kis-keys
    //     await updateKisKey(payload);
    //   } else {
    //     // 미연동 상태 → POST 신규등록
    //     // POST /api/v1/users/me/kis-keys
    //     await registerKisKey(payload);
    //   }
    //   setIsConnected(true);
    //   alert('한국투자증권 API 연동이 완료되었습니다.');
    // } catch (error) {
    //   setKisError(error.message || 'API 연동에 실패했습니다.');
    // } finally {
    //   setKisLoading(false);
    // }
    // ────────────────────────────────────────────────────────────────────────

    // 임시: Mock 저장 처리
    alert('한국투자증권 API 키가 저장되었습니다!');
    setIsConnected(true);
    setKisLoading(false);
  };

  const handleDeleteKisKey = async () => {
    if (!window.confirm('KIS API 연동을 해제하시겠습니까?\n연동 해제 후 자동매매가 중단됩니다.')) return;

    // ── TODO: 백엔드 연동 시 아래 주석 해제 ──────────────────────────────
    // try {
    //   // DELETE /api/v1/users/me/kis-keys
    //   await deleteKisKey();
    //   setIsConnected(false);
    //   setApiKeys({ appKey: '', appSecret: '', accountNo: '' });
    //   alert('KIS API 연동이 해제되었습니다.');
    // } catch (error) {
    //   alert(error.message || '연동 해제에 실패했습니다.');
    // }
    // ────────────────────────────────────────────────────────────────────────

    // 임시: Mock 해제 처리
    setIsConnected(false);
    setApiKeys({ appKey: '', appSecret: '', accountNo: '' });
    alert('KIS API 연동이 해제되었습니다.');
  };

  const handleLogout = async () => {
    // ── TODO: 백엔드 연동 시 아래 주석 해제 ──────────────────────────────
    // try {
    //   // POST /api/v1/auth/logout
    //   // 서버에서 refreshToken 폐기 + 쿠키 만료
    //   await logout();
    //   window.location.href = '/login'; // 전체 상태 초기화를 위해 navigate 대신 href 사용
    // } catch (error) {
    //   console.error('로그아웃 실패:', error);
    //   window.location.href = '/login'; // 에러에도 로그인 페이지로
    // }
    // ────────────────────────────────────────────────────────────────────────

    // 임시
    alert('로그아웃 되었습니다.');
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
                  placeholder="한국투자증권 App Secret를 입력하세요"
                />
                <button type="button" className="eye-btn" onClick={() => setShowSecret(!showSecret)}>
                  {showSecret ? <EyeOff size={20} /> : <Eye size={20} />}
                </button>
              </div>
            </div>
            {/* TODO: 백엔드 accountNo 필드 필수 - '계좌번호-상품코드' 형식 (ex: 50012345-01) */}
            <div className="input-group">
              <label>종합 계좌번호</label>
              <input
                type="text"
                value={apiKeys.accountNo}
                onChange={(e) => setApiKeys({ ...apiKeys, accountNo: e.target.value })}
                placeholder="예: 50012345-01 (계좌번호-상품코드)"
              />
            </div>
            {kisError && <p style={{ color: '#ef4444', fontSize: '0.85rem' }}>{kisError}</p>}
            <div style={{ display: 'flex', gap: '0.8rem' }}>
              <button className="save-btn" onClick={handleSaveKeys} disabled={kisLoading} style={{ flex: 1 }}>
                {kisLoading ? '저장 중...' : '연동 테스트 및 저장'}
              </button>
              {/* TODO: isConnected일 때만 노출 (미연동 상태에서는 삭제할 것이 없음) */}
              {isConnected && (
                <button
                  onClick={handleDeleteKisKey}
                  style={{
                    background: 'rgba(239,68,68,0.1)',
                    color: '#ef4444',
                    border: '1px solid rgba(239,68,68,0.3)',
                    borderRadius: '8px',
                    padding: '0.8rem 1.2rem',
                    cursor: 'pointer',
                    fontWeight: 600,
                    whiteSpace: 'nowrap'
                  }}
                >
                  연동 해제
                </button>
              )}
            </div>
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
        <button className="logout-btn" onClick={handleLogout}>
          로그아웃
        </button>
        <button className="withdraw-btn" onClick={() => {
          if (window.confirm('정말 회원탈퇴를 진행하시겠습니까?\n가지마세요 ㅜㅜ')) {
            // TODO: 회원탈퇴 백엔드 API 구현 후 연동
            alert('회원탈퇴가 완료되었습니다.');
          }
        }}>
          회원탈퇴
        </button>
      </div>

    </div>
  );
}
