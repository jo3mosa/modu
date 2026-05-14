import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Eye, EyeOff } from 'lucide-react';
import { getMyInfo, registerKisKey, updateKisKey, deleteKisKey } from '../api/user';
import { logout } from '../api/auth';
import './MyPage.css';


export default function MyPage() {
  const navigate = useNavigate();
  const [profile, setProfile] = useState({
    name: '',
    email: '',
    provider: '',
    joinedAt: ''
  });

  const [apiKeys, setApiKeys] = useState({
    appKey: '',
    appSecret: '',
    accountNo: ''
  });
  const [isConnected, setIsConnected] = useState(false);
  const [showSecret, setShowSecret] = useState(false);
  const [savingKeys, setSavingKeys] = useState(false);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    // MVP 범위 제외였으나 가시성 확보를 위해 활성화
    async function loadMyInfo() {
      try {
        const data = await getMyInfo();
        setProfile({
          name: data.name,
          email: data.email,
          provider: data.socialProvider || 'Email',
          joinedAt: new Date(data.createdAt).toLocaleDateString()
        });

        if (data.kisKeyStatus && data.kisKeyStatus.isConnected) {
          setIsConnected(true);
          setApiKeys(prev => ({
            ...prev,
            accountNo: data.kisKeyStatus.accountNo || ''
          }));
        }
      } catch (error) {
        console.error('내 정보 로드 실패:', error);
      } finally {
        setIsLoading(false);
      }
    }
    loadMyInfo();
    setIsLoading(false);
  }, []);


  const handleSaveKeys = async () => {
    if (savingKeys) return;
    setSavingKeys(true);
    try {
      if (isConnected) {
        // 연동된 상태에서는 입력된 필드만 PATCH (빈 문자열은 제외)
        const payload = {};
        if (apiKeys.appKey) payload.appKey = apiKeys.appKey;
        if (apiKeys.appSecret) payload.appSecret = apiKeys.appSecret;
        if (apiKeys.accountNo) payload.accountNo = apiKeys.accountNo;

        if (Object.keys(payload).length === 0) {
          alert('변경할 항목을 입력해 주세요.');
          return;
        }
        await updateKisKey(payload);
        alert('한국투자증권 API 키가 수정되었습니다!');
        setIsConnected(true); // 수정 성공 시 연결됨 상태 유지
      } else {
        await registerKisKey({
          appKey: apiKeys.appKey,
          appSecret: apiKeys.appSecret,
          accountNo: apiKeys.accountNo,
          isRealAccount: true // 실전투자 고정
        });
        alert('한국투자증권 API 키가 등록되었습니다!');
        setIsConnected(true);
      }
    } catch (e) {
      console.error('API 연동 실패', e);
      alert(e.message || 'API 연동에 실패했습니다. 키 값을 확인해주세요.');
    } finally {
      setSavingKeys(false);
    }
  };

  const handleLogout = async () => {
    try {
      await logout();
    } catch (e) {
      console.error('로그아웃 실패', e);
    } finally {
      navigate('/login');
    }
  };

  const handleDisconnectKis = async () => {
    if (!window.confirm('정말 연동을 해제하시겠습니까? 자동매매가 중단됩니다.')) return;
    try {
      await deleteKisKey();
      alert('한국투자증권 연동이 해제되었습니다.');
      setIsConnected(false);
      setApiKeys({ appKey: '', appSecret: '', accountNo: '' });
    } catch (e) {
      console.error('연동 해제 실패', e);
      alert(e.message || '연동 해제에 실패했습니다.');
    }
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
          <div className="input-group">
            <label>종합계좌번호</label>
            <input
              type="text"
              value={apiKeys.accountNo}
              onChange={(e) => setApiKeys({ ...apiKeys, accountNo: e.target.value })}
            />
          </div>
          <button className="save-btn" onClick={handleSaveKeys} disabled={savingKeys}>
            {savingKeys ? '저장 중 …' : '연동 테스트 및 저장'}
          </button>
        </div>
      </div>


      <div className="mypage-panel account-actions" style={{ background: 'transparent', border: 'none', boxShadow: 'none', padding: 0 }}>
        <button className="logout-btn" onClick={handleLogout}>
          로그아웃
        </button>
        <button className="withdraw-btn" onClick={handleDisconnectKis}>
          연동 해제
        </button>
      </div>

    </div>
  );
}
